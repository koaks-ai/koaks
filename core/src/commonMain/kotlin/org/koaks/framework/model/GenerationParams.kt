package org.koaks.framework.model

import org.koaks.framework.loop.AgentDSL

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
) {
    /**
     * Layers these (request-level) params over [defaults] (provider-level) field by
     * field: a non-null value here wins, a null falls back to the provider default.
     *
     * This is the single source of truth for the override direction — the more
     * specific layer (the agent's per-request params) overrides the model's defaults
     * declared in the provider DSL. Providers call this in `toWire` so they never
     * re-implement the merge.
     */
    fun over(defaults: GenerationParams): GenerationParams = GenerationParams(
        temperature = temperature ?: defaults.temperature,
        maxTokens = maxTokens ?: defaults.maxTokens,
        topP = topP ?: defaults.topP,
        stop = stop ?: defaults.stop,
        presencePenalty = presencePenalty ?: defaults.presencePenalty,
        frequencyPenalty = frequencyPenalty ?: defaults.frequencyPenalty,
        reasoning = reasoning ?: defaults.reasoning,
    )
}

/**
 * Builder backing the `params { }` DSL block. Shared by the agent builder
 * (request-level params) and every provider config (the model's default params),
 * so both layers are written the same way. Only set the fields you care about;
 * unset fields stay null and either fall back to a lower layer or the provider
 * default. See [GenerationParams.over] for how the layers combine.
 */
@AgentDSL
class GenerationParamsScope {
    var temperature: Double? = null
    var maxTokens: Int? = null
    var topP: Double? = null
    var stop: List<String>? = null
    var presencePenalty: Double? = null
    var frequencyPenalty: Double? = null
    var reasoning: Boolean? = null

    internal fun build(): GenerationParams = GenerationParams(
        temperature = temperature,
        maxTokens = maxTokens,
        topP = topP,
        stop = stop,
        presencePenalty = presencePenalty,
        frequencyPenalty = frequencyPenalty,
        reasoning = reasoning,
    )
}

/** Builds a [GenerationParams] from a `params { }`-style block. */
fun generationParams(block: GenerationParamsScope.() -> Unit): GenerationParams =
    GenerationParamsScope().apply(block).build()
