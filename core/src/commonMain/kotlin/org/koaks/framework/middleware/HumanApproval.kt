package org.koaks.framework.middleware

import org.koaks.framework.model.AgentError
import org.koaks.framework.tool.ToolOutcome

/**
 * Human-in-the-loop approval for tool calls (design §7). Before a guarded tool runs,
 * [approve] is consulted (a suspend hook — wire it to a UI prompt, a queue, etc.).
 * If it returns false the call is denied via the explicit failure channel.
 *
 * By default only tools matching [guard] are gated; the common case is gating
 * side-effecting tools. Supply your own predicate to gate by name or arguments.
 */
class HumanApproval(
    private val guard: (ToolContext) -> Boolean = { true },
    private val approve: suspend (ToolContext) -> Boolean,
) : AgentMiddleware {

    override suspend fun aroundToolCall(
        ctx: ToolContext,
        next: suspend () -> ToolOutcome,
    ): ToolOutcome {
        if (guard(ctx) && !approve(ctx)) {
            return ToolOutcome.Failure(
                AgentError.ToolError(
                    toolName = ctx.call.name,
                    message = "tool '${ctx.call.name}' was not approved by a human reviewer",
                    retriable = false,
                )
            )
        }
        return next()
    }
}
