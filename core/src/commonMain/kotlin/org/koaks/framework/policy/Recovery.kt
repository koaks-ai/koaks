package org.koaks.framework.policy

import org.koaks.framework.loop.AgentState
import org.koaks.framework.model.AgentError
import org.koaks.framework.model.Message

/**
 * How the loop should recover from an [AgentError]. These are control-flow
 * decisions consumed by the loop, not middleware.
 */
sealed interface Recovery {
    /** Abort the agent, surfacing the error. */
    data object Propagate : Recovery

    /** Re-run the current step after [delayMs]. Only safe before any TextDelta was emitted. */
    data class Retry(val delayMs: Long, val maxRetries: Int) : Recovery

    /** Continue with a substitute message instead of retrying. */
    data class Substitute(val message: Message) : Recovery
}

/**
 * Decides the [Recovery] for an error. Invoked by the loop, not as middleware.
 * The default propagates everything.
 */
fun interface ErrorPolicy {
    fun decide(error: AgentError, state: AgentState): Recovery

    companion object {
        /** Always propagate (the default). */
        val PROPAGATE: ErrorPolicy = ErrorPolicy { _, _ -> Recovery.Propagate }

        /**
         * Retries retriable errors up to [maxRetries] with [delayMs] backoff; everything
         * else propagates. The loop only honors a retry before any TextDelta was emitted
         * this step, so this is safe to combine with streaming.
         */
        fun retryRetriable(maxRetries: Int = 2, delayMs: Long = 200): ErrorPolicy =
            ErrorPolicy { error, _ ->
                if (error.isRetriable()) Recovery.Retry(delayMs, maxRetries) else Recovery.Propagate
            }

        /**
         * Substitutes a fixed assistant [fallbackMessage] instead of failing — useful
         * to keep a conversation alive after an unrecoverable model error.
         */
        fun substituteOnError(fallbackMessage: Message): ErrorPolicy =
            ErrorPolicy { _, _ -> Recovery.Substitute(fallbackMessage) }

        private fun AgentError.isRetriable(): Boolean = when (this) {
            is AgentError.ModelError -> retriable
            is AgentError.ToolError -> retriable
            is AgentError.Timeout -> true
            is AgentError.ParseError -> false
            is AgentError.ToolNotFound -> false
        }
    }
}
