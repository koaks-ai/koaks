package org.koaks.runtime.acb

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.koaks.framework.loop.AgentEvent
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
    val id: AgentId,
    agentName: String,
    priority: Int,
    parent: AgentId?,
) {
    private val _state = MutableStateFlow(
        AcbSnapshot(
            id = id,
            agentName = agentName,
            state = LifecycleState.CREATED,
            priority = priority,
            parent = parent,
            children = emptyList(),
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

    private var startMark: TimeSource.Monotonic.ValueTimeMark? = null

    private fun elapsed(): Long =
        startMark?.elapsedNow()?.inWholeMilliseconds ?: 0L

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
        if (startMark == null) startMark = TimeSource.Monotonic.markNow()
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
                cur.state.isTerminal -> cur // don't overwrite FINISHED / FAILED
                else -> cur.copy(state = LifecycleState.CANCELLED, elapsedMillis = elapsed())
            }
        }
    }

    internal fun setState(to: LifecycleState) = transition(to)

    internal fun addChild(child: AgentId) {
        _state.update { it.copy(children = it.children + child) }
    }
}

/** Immutable, externally-visible view of an [Acb]. */
data class AcbSnapshot(
    val id: AgentId,
    val agentName: String,
    val state: LifecycleState,
    val priority: Int,
    val parent: AgentId?,
    val children: List<AgentId>,
    val usage: Usage,
    val stepsCompleted: Int,
    val toolCalls: Int,
    val elapsedMillis: Long,
    val error: AgentError?,
)
