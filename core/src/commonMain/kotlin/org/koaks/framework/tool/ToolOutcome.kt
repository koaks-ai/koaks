package org.koaks.framework.tool

import org.koaks.framework.model.AgentError

/**
 * The result of a tool invocation. Errors travel through the explicit [Failure]
 * channel — the framework never fabricates a string result to feed back to the
 * model (which was the old silent-error bug).
 */
sealed interface ToolOutcome {
    data class Success(val output: String, val returnDirectly: Boolean = false) : ToolOutcome
    data class Failure(val error: AgentError) : ToolOutcome
}
