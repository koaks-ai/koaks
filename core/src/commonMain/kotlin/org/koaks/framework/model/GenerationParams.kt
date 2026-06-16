package org.koaks.framework.model

/**
 * Provider-agnostic sampling / generation parameters. Providers map the subset
 * they support into their own wire format; unknown fields are ignored.
 *
 * @property reasoning when true, ask the model to expose its reasoning/thinking trace
 *   (Qwen `enable_thinking`, Ollama `think`). Surfaced as `ReasoningDelta` events; null
 *   leaves the provider default untouched.
 */
data class GenerationParams(
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val topP: Double? = null,
    val stop: List<String>? = null,
    val presencePenalty: Double? = null,
    val frequencyPenalty: Double? = null,
    val reasoning: Boolean? = null,
)
