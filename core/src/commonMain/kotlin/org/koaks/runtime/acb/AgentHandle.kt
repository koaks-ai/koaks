package org.koaks.runtime.acb

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.koaks.framework.loop.AgentExecutionContext
import org.koaks.framework.loop.AgentResult
import org.koaks.framework.loop.AgentId
import org.koaks.framework.memory.ThreadId
import org.koaks.framework.model.AgentError
import org.koaks.runtime.resource.ChildFailurePolicy

/**
 * A handle to a spawned agent run instance — the analogue of a pid returned by the
 * kernel. Lets callers await the result, observe the [Acb], and control the instance
 * (cancel / suspend / resume). Cheap to pass around; the actual work runs in the
 * runtime's scope.
 */
class AgentHandle internal constructor(
    val runId: RunId,
    val agentId: AgentId,
    val threadId: ThreadId?,
    val turnId: TurnId?,
    private val acb: Acb,
    private val control: InstanceControl,
    private val deferred: Deferred<AgentResult>,
    private val failurePolicy: ChildFailurePolicy,
) {
    private val failureObserved = MutableStateFlow(false)
    private val unhandledFailureReported = MutableStateFlow(false)

    /** A point-in-time view of this instance's control block. */
    val snapshot: AcbSnapshot get() = acb.snapshot

    /** Observable control-block updates. */
    val updates: StateFlow<AcbSnapshot> get() = acb.updates

    /** Current lifecycle state (shortcut for `snapshot.state`). */
    val state: LifecycleState get() = acb.snapshot.state

    /** Whether the underlying coroutine is still active. */
    val isActive: Boolean get() = deferred.isActive

    /**
     * Awaits the terminal [AgentResult]. Caller cancellation is always rethrown. When a
     * CAPTURE child is independently cancelled, the cancellation is returned as a failed
     * result so the supervising caller can handle it without being cancelled itself.
     *
     * When awaited from inside another runtime instance (a tool spawning and awaiting a
     * child), the awaiting branch is marked waiting via the ambient [AgentExecutionContext]
     * — releasing the instance's scheduler slot if no other branch is runnable — and
     * restored before this returns, so a parent awaiting a child never deadlocks the
     * scheduler, even at `maxConcurrency = 1`. Outside a runtime the wait simply blocks.
     */
    suspend fun await(): AgentResult {
        return try {
            awaitInternal().also { result ->
                if (result is AgentResult.Failed) failureObserved.value = true
            }
        } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
            failureObserved.value = true
            if (failurePolicy == ChildFailurePolicy.CAPTURE) {
                AgentResult.Failed(
                    error = AgentError.ModelError(
                        message = cancelled.message ?: "child run was cancelled",
                        retriable = false,
                        cause = cancelled,
                    ),
                    usage = snapshot.usage,
                )
            } else {
                throw cancelled
            }
        }
    }

    /** Runtime-only wait that does not count as the caller consuming a captured failure. */
    internal suspend fun awaitForParent(): AgentResult = awaitInternal()

    internal val isFailureObserved: Boolean get() = failureObserved.value

    internal fun claimUnhandledFailure(): Boolean =
        unhandledFailureReported.compareAndSet(expect = false, update = true)

    private suspend fun awaitInternal(): AgentResult {
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

    /**
     * Cooperatively cancels the instance. The ACB moves to CANCELLED immediately unless
     * the run has already entered COMMITTING, where durability is allowed to finish.
     */
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
