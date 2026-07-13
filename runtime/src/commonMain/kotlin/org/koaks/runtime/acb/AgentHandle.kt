package org.koaks.runtime.acb

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import org.koaks.framework.loop.AgentEvent
import org.koaks.framework.loop.AgentResult

/**
 * A handle to a spawned agent run instance — the analogue of a pid returned by the
 * kernel. Lets callers await the result, observe the [Acb], and control the instance
 * (cancel / suspend / resume). Cheap to pass around; the actual work runs in the
 * runtime's scope.
 */
class AgentHandle internal constructor(
    val id: AgentId,
    private val acb: Acb,
    private val control: InstanceControl,
    private val deferred: Deferred<AgentResult>,
    /**
     * The instance's outward event stream — its stdout. Carries incremental
     * [AgentEvent.TextDelta] / [AgentEvent.ReasoningDelta], tool calls, step and terminal
     * events. Populated only when the instance was spawned with `observe = true`; otherwise
     * an empty flow. Observed events are retained losslessly, so collection can happen while
     * the agent runs or after [await] without blocking execution. The flow permits exactly one
     * collector; a second collection fails instead of splitting events. If that collector stops
     * early, buffered and subsequent events are discarded without affecting the agent run.
     * Because events are retained until collection begins, enable observation only when the
     * stream will be consumed.
     *
     * Runtime-boundary stops always end the stream with a terminal event and then complete
     * normally: quota → [AgentEvent.Terminated], wall-clock timeout → [AgentEvent.Failed],
     * cancel (operator / parent / runtime teardown) → [AgentEvent.Terminated]. [await] still
     * throws [CancellationException] on cancel; only the event stream is normalized.
     */
    val events: Flow<AgentEvent>,
) {
    /** A point-in-time view of this instance's control block. */
    val snapshot: AcbSnapshot get() = acb.snapshot

    /** Observable control-block updates. */
    val updates: StateFlow<AcbSnapshot> get() = acb.updates

    /** Current lifecycle state (shortcut for `snapshot.state`). */
    val state: LifecycleState get() = acb.snapshot.state

    /** Whether the underlying coroutine is still active. */
    val isActive: Boolean get() = deferred.isActive

    /** Awaits the terminal [AgentResult]. Rethrows cancellation. */
    suspend fun await(): AgentResult = deferred.await()

    /** Joins without retrieving the result (never throws the instance's failure). */
    suspend fun join() = deferred.join()

    /** Cooperatively cancels the instance. The ACB moves to CANCELLED immediately. */
    fun cancel(reason: String? = null) {
        // Mark the ACB first so a cancel that races ahead of the coroutine body
        // (before runInstance's catch runs) still leaves a correct terminal state.
        acb.markCancelled()
        deferred.cancel(CancellationException(reason ?: "cancelled by operator"))
    }

    /** Requests a cooperative pause; takes effect at the next event boundary. */
    fun pause() {
        control.pause()
    }

    /** Resumes a paused instance. */
    fun resume() {
        control.resume()
    }
}
