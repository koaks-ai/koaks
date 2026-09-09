package org.koaks.runtime.run

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.koaks.framework.loop.AgentExecutionContext
import org.koaks.framework.loop.AgentId
import org.koaks.framework.loop.AgentResult
import org.koaks.framework.memory.ThreadId
import org.koaks.framework.model.AgentError
import org.koaks.runtime.internal.RunControl
import org.koaks.runtime.internal.RunJournal
import org.koaks.runtime.internal.RunRecord
import org.koaks.runtime.policy.ChildFailurePolicy
import org.koaks.runtime.coordination.SendResult

/**
 * A handle to one run. Lets callers await the result, observe [RunSnapshot], and control execution
 * (cancel / suspend / resume). Cheap to pass around; the actual work runs in the
 * runtime's scope.
 */
class RunHandle internal constructor(
    val runId: RunId,
    val agentId: AgentId,
    val threadId: ThreadId?,
    val correlationId: String?,
    private val record: RunRecord,
    private val control: RunControl,
    private val deferred: Deferred<AgentResult>,
    private val failurePolicy: ChildFailurePolicy,
    private val eventJournal: RunJournal,
    private val sendMessage: (suspend (String) -> SendResult)? = null,
) {
    private val failureObserved = MutableStateFlow(false)
    private val unhandledFailureReported = MutableStateFlow(false)

    /** A point-in-time view of this instance's run. */
    val snapshot: RunSnapshot get() = record.snapshot

    /** Observable run snapshot updates. */
    val updates: StateFlow<RunSnapshot> get() = record.updates

    /** Ordered lifecycle and content events retained by this run's bounded journal. */
    fun events(afterSequence: Long? = null): Flow<RunEventEnvelope> = eventJournal.events(afterSequence)

    /** Current lifecycle state (shortcut for `snapshot.state`). */
    val state: RunState get() = record.snapshot.state

    /** Whether the underlying coroutine is still active. */
    val isActive: Boolean get() = deferred.isActive

    /** Sends a message to this run's inbox. A null sender denotes an external caller. */
    suspend fun send(message: String): SendResult =
        sendMessage?.invoke(message) ?: SendResult.Rejected("message sending is unavailable")

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
     * Cooperatively cancels the instance. The run record moves to CANCELLED immediately unless
     * the run has already entered COMMITTING, where durability is allowed to finish.
     */
    fun cancel(reason: String? = null) {
        // Mark the run record first so a cancel that races ahead of the coroutine body
        // (before runInstance's catch runs) still leaves a correct terminal state.
        record.markCancelled()
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
