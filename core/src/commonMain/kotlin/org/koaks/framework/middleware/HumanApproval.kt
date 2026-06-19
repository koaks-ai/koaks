package org.koaks.framework.middleware

/**
 * Human-in-the-loop approval for tool calls. Before a guarded tool runs,
 * [approve] is consulted (a suspend hook — wire it to a UI prompt, a queue, etc.).
 * If it returns false the call is denied via the explicit failure channel.
 *
 * By default only tools matching [guard] are gated; the common case is gating
 * side-effecting tools. Supply your own predicate to gate by name or arguments.
 */
class HumanApproval(
    private val guard: (ToolContext) -> Boolean = { true },
    private val approve: suspend (ToolContext) -> Boolean,
) : Hook {

    override suspend fun onToolCall(ctx: ToolContext): ToolDecision {
        if (guard(ctx) && !approve(ctx)) {
            return ToolDecision.Deny("tool '${ctx.call.name}' was not approved by a human reviewer")
        }
        return ToolDecision.Proceed
    }
}
