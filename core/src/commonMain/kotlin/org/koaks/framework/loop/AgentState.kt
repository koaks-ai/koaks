package org.koaks.framework.loop

import org.koaks.framework.model.Message
import org.koaks.framework.model.ToolCall
import org.koaks.framework.model.Usage
import org.koaks.framework.tool.ToolOutcome

/**
 * The strongly-typed agent loop state — a `data class`, NOT a `Map<String, Any>`.
 *
 * @property messages the working set for this run.
 * @property globalStep steps accumulated across the whole run (never reset; for the
 *   future RunBudget global guard in L5).
 * @property localStep steps since the current agent took over (reset on handoff in L5).
 * @property usage accumulated token usage.
 * @property activeAgentName name of the currently active agent.
 */
data class AgentState(
    val messages: List<Message>,
    val globalStep: Int = 0,
    val localStep: Int = 0,
    val usage: Usage = Usage.ZERO,
    val activeAgentName: String = "agent",
) {
    /** The step number to report for the step just completed. */
    val step: Int get() = globalStep

    fun append(message: Message): AgentState =
        copy(messages = messages + message, globalStep = globalStep + 1, localStep = localStep + 1)

    fun addUsage(delta: Usage): AgentState = copy(usage = usage + delta)

    /** Appends the tool result messages for the given calls/outcomes (positional). */
    fun appendToolResults(calls: List<ToolCall>, outcomes: List<ToolOutcome>): AgentState {
        val toolMessages = calls.mapIndexed { i, call ->
            when (val o = outcomes[i]) {
                is ToolOutcome.Success -> Message.tool(call.id, o.output, isError = false)
                is ToolOutcome.Failure -> Message.tool(call.id, o.error.message, isError = true)
            }
        }
        return copy(messages = messages + toolMessages)
    }

    fun lastAssistantOrEmpty(): Message =
        messages.lastOrNull { it.role == org.koaks.framework.model.Role.ASSISTANT }
            ?: Message.assistant("")
}
