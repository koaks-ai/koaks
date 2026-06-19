package org.koaks.framework.middleware

/**
 * A guardrail that can block tool calls before they execute. The
 * [check] returns null to allow the call, or a reason string to deny it. A denied
 * call becomes a tool failure without invoking the tool, surfaced by the loop on
 * the explicit error channel like any other tool failure.
 */
class Guardrail(
    private val check: (ToolContext) -> String?,
) : Hook {

    override suspend fun onToolCall(ctx: ToolContext): ToolDecision {
        val denial = check(ctx)
        return denial?.let { ToolDecision.Deny("guardrail blocked '${ctx.call.name}': $it") }
            ?: ToolDecision.Proceed
    }
}
