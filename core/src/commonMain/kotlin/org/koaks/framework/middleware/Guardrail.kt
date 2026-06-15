package org.koaks.framework.middleware

import org.koaks.framework.model.AgentError
import org.koaks.framework.tool.ToolOutcome

/**
 * A guardrail that can block tool calls before they execute. The
 * [check] returns null to allow the call, or a reason string to deny it. A denied
 * call short-circuits to a [ToolOutcome.Failure] without invoking the tool — the
 * loop surfaces it on the explicit error channel like any other tool failure.
 */
class Guardrail(
    private val check: (ToolContext) -> String?,
) : AgentMiddleware {

    override suspend fun aroundToolCall(
        ctx: ToolContext,
        next: suspend () -> ToolOutcome,
    ): ToolOutcome {
        val denial = check(ctx)
        return if (denial != null) {
            ToolOutcome.Failure(
                AgentError.ToolError(
                    toolName = ctx.call.name,
                    message = "guardrail blocked '${ctx.call.name}': $denial",
                    retriable = false,
                )
            )
        } else {
            next()
        }
    }
}
