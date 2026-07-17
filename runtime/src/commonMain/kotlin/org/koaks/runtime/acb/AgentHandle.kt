package org.koaks.runtime.acb

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.StateFlow
import org.koaks.framework.loop.AgentExecutionContext
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

    /**
     * Awaits the terminal [AgentResult]. Rethrows cancellation.
     *
     * When awaited from inside another runtime instance (a tool spawning and awaiting a
     * child), the awaiting branch is marked waiting via the ambient [AgentExecutionContext]
     * — releasing the instance's scheduler slot if no other branch is runnable — and
     * restored before this returns, so a parent awaiting a child never deadlocks the
     * scheduler, even at `maxConcurrency = 1`. Outside a runtime the wait simply blocks.
     */
    suspend fun await(): AgentResult {
        if (deferred.isCompleted) return deferred.await()
        val exec = currentCoroutineContext()[AgentExecutionContext]
        return if (exec != null) exec.waiting { deferred.await() } else deferred.await()
    }

    /** Joins without retrieving the result (never throws the instance's failure). */
    suspend fun join() {
        if (deferred.isCompleted) return
        val exec = currentCoroutineContext()[AgentExecutionContext]
        if (exec != null) exec.waiting { deferred.join() } else deferred.join()
    }

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
