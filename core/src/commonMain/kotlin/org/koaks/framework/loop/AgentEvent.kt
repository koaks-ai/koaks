package org.koaks.framework.loop

import org.koaks.framework.model.AgentError
import org.koaks.framework.model.Message
import org.koaks.framework.model.ToolCall
import org.koaks.framework.model.Usage
import org.koaks.framework.tool.ToolOutcome

/**
 * The outward-facing streaming primitive, produced by [AgentRunner]. Translated
 * from [org.koaks.framework.model.ModelEvent] plus tool/termination state.
 */
sealed interface AgentEvent {
    /** Incremental assistant text, forwarded immediately (tee), not buffered. */
    data class TextDelta(val text: String) : AgentEvent

    /**
     * Incremental reasoning/thinking text, forwarded immediately (tee). Distinct from
     * [TextDelta]: it is the model's thinking trace, not part of the final answer, and
     * is never persisted to the message history.
     */
    data class ReasoningDelta(val text: String) : AgentEvent

    /** The model requested a (complete) tool call. */
    data class ToolCallRequested(val call: ToolCall) : AgentEvent

    /** A tool finished; [isError] marks failures surfaced through the explicit channel. */
    data class ToolResult(val callId: String, val output: String, val isError: Boolean) : AgentEvent

    /** A model step completed. */
    data class StepCompleted(val step: Int) : AgentEvent

    /** The agent reached a terminal answer. */
    data class Finished(val message: Message, val usage: Usage) : AgentEvent

    /**
     * An error was surfaced. May be non-terminal (e.g. a tool error fed back to the
     * model) or terminal (loop is propagating). Consumers should look for [Finished]
     * to know the run succeeded.
     */
    data class Failed(val error: AgentError) : AgentEvent
}

internal fun ToolOutcome.toEvent(callId: String): AgentEvent = when (this) {
    is ToolOutcome.Success -> AgentEvent.ToolResult(callId, output, isError = false)
    is ToolOutcome.Failure -> AgentEvent.ToolResult(callId, error.message, isError = true)
}
