package org.koaks.runtime.fault

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import org.koaks.framework.loop.AgentResult

/**
 * Handle to a supervised run (one logical task that may span several instance attempts).
 * [await] returns the final result after retries/backoff; [cancel] stops the current
 * attempt and the supervision loop.
 */
class SupervisedHandle internal constructor(
    private val deferred: Deferred<AgentResult>,
    private val onCancel: (String?) -> Unit,
) {
    /** Awaits the final result across all attempts. */
    suspend fun await(): AgentResult = deferred.await()

    /** Cancels the supervised run (current attempt + retry loop). */
    fun cancel(reason: String? = null) {
        onCancel(reason)
        deferred.cancel(CancellationException(reason ?: "supervised run cancelled"))
    }
}
