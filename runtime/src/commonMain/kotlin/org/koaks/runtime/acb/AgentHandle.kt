package org.koaks.runtime.acb

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.StateFlow
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
