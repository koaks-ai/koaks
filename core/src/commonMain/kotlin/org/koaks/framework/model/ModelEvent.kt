package org.koaks.framework.model

/**
 * Model-layer streaming primitive. Only expresses what a provider can genuinely
 * produce — it carries NO tool execution, step, or terminal agent-loop semantics.
 * The [org.koaks.framework.loop.AgentRunner] translates these into `AgentEvent`s.
 */
sealed interface ModelEvent {

    /** An incremental chunk of assistant text. */
    data class TextDelta(val text: String) : ModelEvent

    /**
     * An incremental chunk of the model's reasoning/thinking trace (e.g. Qwen/DeepSeek
     * `reasoning_content`, Ollama `thinking`). Carried separately from [TextDelta] so
     * consumers can surface it distinctly; it is NOT part of the assistant message and
     * is never fed back to the model on a subsequent step.
     */
    data class ReasoningDelta(val text: String) : ModelEvent

    /**
     * A fragment of a tool call. Provider streams deliver a tool call's name and
     * arguments across multiple chunks; the decoder accumulates these.
     */
    data class ToolCallDelta(
        val id: String,
        val index: Int? = null,
        val nameDelta: String? = null,
        val argumentsDelta: String? = null,
    ) : ModelEvent

    /** A fully assembled tool call, emitted once the decoder has all fragments. */
    data class ToolCallCompleted(val call: ToolCall) : ModelEvent

    /** The model call finished normally; carries final token usage. */
    data class Completed(val usage: Usage) : ModelEvent

    /** The provider explicitly reported a failure. Carries a first-class [AgentError]. */
    data class Failed(val error: AgentError.ModelError) : ModelEvent
}
