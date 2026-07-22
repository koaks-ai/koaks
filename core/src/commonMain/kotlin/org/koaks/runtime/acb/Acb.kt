package org.koaks.runtime.acb

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.koaks.framework.loop.AgentEvent
import org.koaks.framework.loop.AgentId
import org.koaks.framework.memory.ThreadId
import org.koaks.framework.model.AgentError
import org.koaks.framework.model.Usage
import kotlin.time.TimeSource

/**
 * Agent Control Block — the runtime's per-instance bookkeeping, analogous to a PCB.
 *
 * The mutable state is kept behind a [MutableStateFlow] so reads are lock-free and
 * observable ([updates]); compound mutations use atomic [update] (CAS). An immutable
 * [AcbSnapshot] is what leaks to callers.
 */
class Acb internal constructor(
    val runId: RunId,
    agentId: AgentId,
    agentName: String,
    threadId: ThreadId?,
    turnId: TurnId?,
    priority: Int,
    parent: RunId?,
) {
    private val cancellationReported = MutableStateFlow(false)
    private val _state = MutableStateFlow(
        AcbSnapshot(
            runId = runId,
            agentId = agentId,
            agentName = agentName,
            threadId = threadId,
            turnId = turnId,
            state = LifecycleState.CREATED,
            priority = priority,
            parent = parent,
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
    val snapshot: AcbSnapshot get() = _state.value

    /** Observable state, updated as the instance progresses. */
    val updates: StateFlow<AcbSnapshot> get() = _state.asStateFlow()

    private val startMark: TimeSource.Monotonic.ValueTimeMark = TimeSource.Monotonic.markNow()

    private fun elapsed(): Long =
        startMark.elapsedNow().inWholeMilliseconds

    internal fun transition(to: LifecycleState) {
        _state.update { cur ->
            if (cur.state == to || cur.state.canTransitionTo(to)) {
                cur.copy(state = to, elapsedMillis = elapsed())
            } else {
                cur
            }
        }
    }

    internal fun markReady() = transition(LifecycleState.READY)

    internal fun markRunning() {
        transition(LifecycleState.RUNNING)
    }

    /** Folds one outward [AgentEvent] into the block's counters. */
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
        _state.update { it.copy(state = LifecycleState.FINISHED, usage = usage, elapsedMillis = elapsed()) }
    }

    internal fun markFailed(error: AgentError, usage: Usage) {
        _state.update {
            it.copy(
                state = LifecycleState.FAILED,
                error = error,
                usage = if (usage != Usage.ZERO) usage else it.usage,
                elapsedMillis = elapsed(),
            )
        }
    }

    internal fun markCancelled() {
        _state.update { cur ->
            when {
                cur.state == LifecycleState.CANCELLED -> cur.copy(elapsedMillis = elapsed())
                cur.state == LifecycleState.COMMITTING -> cur
                cur.state.isTerminal -> cur // don't overwrite FINISHED / FAILED
                else -> cur.copy(state = LifecycleState.CANCELLED, elapsedMillis = elapsed())
            }
        }
    }

    /** Atomically lets successful completion win against a racing cancellation. */
    internal fun beginCommitting(): Boolean {
        while (true) {
            val current = _state.value
            if (current.state.isTerminal) return false
            if (current.state == LifecycleState.COMMITTING) return true
            val next = current.copy(state = LifecycleState.COMMITTING, elapsedMillis = elapsed())
            if (_state.compareAndSet(current, next)) return true
        }
    }

    internal fun setState(to: LifecycleState) = transition(to)

    internal fun tryAddChild(child: RunId): Boolean {
        while (true) {
            val current = _state.value
            if (!current.acceptingChildren || current.state.isTerminal) return false
            if (_state.compareAndSet(current, current.copy(children = current.children + child))) return true
        }
    }

    /** Claims the single RuntimeEvent.Cancelled emission for this run. */
    internal fun claimCancellationEvent(): Boolean =
        cancellationReported.compareAndSet(expect = false, update = true)

    internal fun removeChild(child: RunId) {
        _state.update { it.copy(children = it.children - child) }
    }

    internal fun sealChildren() {
        _state.update { it.copy(acceptingChildren = false) }
    }
}

/** Immutable, externally-visible view of an [Acb]. */
data class AcbSnapshot(
    val runId: RunId,
    val agentId: AgentId,
    val agentName: String,
    val threadId: ThreadId?,
    val turnId: TurnId?,
    val state: LifecycleState,
    val priority: Int,
    val parent: RunId?,
    val children: List<RunId>,
    val acceptingChildren: Boolean,
    val usage: Usage,
    val stepsCompleted: Int,
    val toolCalls: Int,
    val elapsedMillis: Long,
    val error: AgentError?,
)
