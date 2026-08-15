package org.koaks.framework.policy

import org.koaks.framework.loop.AgentState
import org.koaks.framework.model.AgentError
import org.koaks.framework.model.ModelItem

sealed interface Recovery {
    data object Propagate : Recovery

    data class Retry(val delayMs: Long, val maxRetries: Int) : Recovery

    data class Substitute(val message: ModelItem) : Recovery
}

fun interface ErrorPolicy {
    fun decide(error: AgentError, state: AgentState): Recovery

    companion object {
        val PROPAGATE: ErrorPolicy = ErrorPolicy { _, _ -> Recovery.Propagate }

        fun retryRetriable(maxRetries: Int = 2, delayMs: Long = 200): ErrorPolicy =
            ErrorPolicy { error, _ ->
                if (error.isRetriable()) Recovery.Retry(delayMs, maxRetries) else Recovery.Propagate
            }

        fun substituteOnError(fallbackMessage: ModelItem): ErrorPolicy =
            ErrorPolicy { _, _ -> Recovery.Substitute(fallbackMessage) }

        private fun AgentError.isRetriable(): Boolean = when (this) {
            is AgentError.ModelError -> retriable
            is AgentError.ToolError -> retriable
            is AgentError.Timeout -> true
            is AgentError.ParseError -> false
            is AgentError.ToolNotFound -> false
            is AgentError.SkillError -> false
            is AgentError.PreparationError -> false
            else -> false
        }
    }
}
