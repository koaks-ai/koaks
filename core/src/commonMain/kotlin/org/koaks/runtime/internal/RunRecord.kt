package org.koaks.runtime.internal

import kotlin.time.TimeSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.koaks.framework.loop.AgentEvent
import org.koaks.framework.loop.AgentId
import org.koaks.framework.memory.ThreadId
import org.koaks.framework.model.AgentError
import org.koaks.framework.model.Usage
import org.koaks.runtime.policy.ChildFailurePolicy
import org.koaks.runtime.run.RunId
import org.koaks.runtime.run.RunSnapshot
import org.koaks.runtime.run.RunState

/**
 * Mutable bookkeeping for one run; only immutable snapshots are exposed to callers.
 *
 * The mutable state is kept behind a [MutableStateFlow] so reads are lock-free and
 * observable ([updates]); compound mutations use atomic [update] (CAS). An immutable
 * [RunSnapshot] is the public observation interface.
 */
internal class RunRecord(
    val runId: RunId,
    agentId: AgentId,
    agentName: String,
    threadId: ThreadId?,
    priority: Int,
    parent: RunId?,
    correlationId: String?,
) {
    private val cancellationReported = MutableStateFlow(false)
    private val childFailurePolicies = MutableStateFlow<Map<RunId, ChildFailurePolicy>>(emptyMap())
    private val _state = MutableStateFlow(
        RunSnapshot(
            runId = runId,
            agentId = agentId,
            agentName = agentName,
            threadId = threadId,
            state = RunState.CREATED,
            priority = priority,
            parent = parent,
            correlationId = correlationId,
            children = emptyList(),
            acceptingChildren = true,
            usage = Usage.ZERO,
            stepsCompleted = 0,
            toolCalls = 0,
            elapsedMillis = 0,
            error = null,
        ),
    )

    /** A point-in-time immutable view. */
    val snapshot: RunSnapshot get() = _state.value

    /** Observable state, updated as the instance progresses. */
    val updates: StateFlow<RunSnapshot> get() = _state.asStateFlow()

    private val startMark: TimeSource.Monotonic.ValueTimeMark = TimeSource.Monotonic.markNow()

    private fun elapsed(): Long =
        startMark.elapsedNow().inWholeMilliseconds

    /** The sole state mutation path, including terminal transitions and their counters. */
    private fun transition(to: RunState, update: (RunSnapshot) -> RunSnapshot = { it }): Boolean {
        while (true) {
            val current = _state.value
            if (current.state != to && !current.state.canTransitionTo(to)) return false
            val next = update(current).copy(state = to, elapsedMillis = elapsed())
            if (_state.compareAndSet(current, next)) return true
        }
    }

    internal fun markThreadQueued() { transition(RunState.THREAD_QUEUED) }
    internal fun markReady() { transition(RunState.READY) }
    internal fun markRunning() { transition(RunState.RUNNING) }
    internal fun markWaiting() { transition(RunState.WAITING) }
    internal fun markPaused() { transition(RunState.SUSPENDED) }

    /** Folds one outward [AgentEvent] into the run's counters. */
    internal fun observe(event: AgentEvent) {
        _state.update { cur ->
            when (event) {
                is AgentEvent.StepCompleted -> cur.copy(stepsCompleted = cur.stepsCompleted + 1, elapsedMillis = elapsed())
                is AgentEvent.ToolCallRequested -> cur.copy(toolCalls = cur.toolCalls + 1, elapsedMillis = elapsed())
                is AgentEvent.Terminal -> cur.copy(usage = event.usage, elapsedMillis = elapsed())
                is AgentEvent.Failed -> cur.copy(
                    usage = if (event.usage != Usage.ZERO) event.usage else cur.usage,
                    elapsedMillis = elapsed(),
                )
                else -> cur.copy(elapsedMillis = elapsed())
            }
        }
    }

    internal fun markFinished(usage: Usage) {
        transition(RunState.FINISHED) { it.copy(usage = usage) }
    }

    internal fun markFailed(error: AgentError, usage: Usage) {
        transition(RunState.FAILED) {
            it.copy(error = error, usage = if (usage != Usage.ZERO) usage else it.usage)
        }
    }

    internal fun markCancelled() { transition(RunState.CANCELLED) }

    /** Atomically lets successful completion win against a racing cancellation. */
    internal fun beginCommitting(): Boolean = transition(RunState.COMMITTING)

    internal fun tryAddChild(child: RunId, failurePolicy: ChildFailurePolicy): Boolean {
        while (true) {
            val current = _state.value
            if (!current.acceptingChildren || current.state.isTerminal) return false
            if (_state.compareAndSet(current, current.copy(children = current.children + child))) {
                childFailurePolicies.update { it + (child to failurePolicy) }
                return true
            }
        }
    }

    internal fun childFailurePolicy(child: RunId): ChildFailurePolicy =
        childFailurePolicies.value[child]
            ?: error("child run '${child.value}' is not linked to parent '${runId.value}'")

    /** Claims the single RuntimeEvent.Cancelled emission for this run. */
    internal fun claimCancellationEvent(): Boolean =
        cancellationReported.compareAndSet(expect = false, update = true)

    internal fun removeChild(child: RunId) {
        _state.update { it.copy(children = it.children - child) }
        childFailurePolicies.update { it - child }
    }

    internal fun sealChildren() {
        _state.update { it.copy(acceptingChildren = false) }
    }
}
