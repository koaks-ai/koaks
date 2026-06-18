package org.koaks.provider.openai

import org.koaks.framework.loop.AgentDSL
import org.koaks.framework.loop.ModelScope
import org.koaks.framework.loop.ModelSelection
import org.koaks.framework.model.ModelCapabilities
import org.koaks.framework.provider.ModelConfig

/** OpenAI's default Chat Completions endpoint. */
const val OPENAI_DEFAULT_BASE_URL: String = "https://api.openai.com/v1/chat/completions"

/**
 * Configuration scope for the OpenAI provider DSL: `model { openai(...) { ... } }`.
 * Generation params are OpenAI-native and set flat on this block; only fields that
 * differ from defaults need to be set.
 */
@AgentDSL
class OpenAIConfig(
    var baseUrl: String,
    var apiKey: String,
    var modelName: String,
) {
    // OpenAI-native generation params, bound to this model.
    var temperature: Double? = null

    /** Maps to OpenAI's `max_completion_tokens` (replaces the deprecated `max_tokens`). */
    var maxCompletionTokens: Int? = null
    var topP: Double? = null
    var stop: List<String>? = null
    var presencePenalty: Double? = null
    var frequencyPenalty: Double? = null

    /** Maps to OpenAI's `reasoning_effort`: `"low" | "medium" | "high"`. */
    var reasoningEffort: String? = null

    private var caps = ModelCapabilities()
    fun capabilities(block: OpenAICapabilitiesScope.() -> Unit) {
        caps = OpenAICapabilitiesScope(caps).apply(block).build()
    }

    internal fun toConfig(): ModelConfig = ModelConfig(
        baseUrl = baseUrl,
        apiKey = apiKey,
        modelName = modelName,
    )

    internal fun params(): OpenAIParams = OpenAIParams(
        temperature = temperature,
        maxCompletionTokens = maxCompletionTokens,
        topP = topP,
        stop = stop,
        presencePenalty = presencePenalty,
        frequencyPenalty = frequencyPenalty,
        reasoningEffort = reasoningEffort,
    )

    internal fun capabilities(): ModelCapabilities = caps
}

@AgentDSL
class OpenAICapabilitiesScope(initial: ModelCapabilities) {
    var parallelToolCalls: Boolean = initial.parallelToolCalls
    var vision: Boolean = initial.vision
    var jsonMode: Boolean = initial.jsonMode

    internal fun build() = ModelCapabilities(parallelToolCalls, vision, jsonMode)
}

/**
 * Selects OpenAI (Chat Completions) as the agent's model. Builds an [OpenAIChatModel]
 * using the transport from [ModelScope] (agent-owned unless externally injected),
 * returning it as a [ModelSelection] so callers can chain `.fallback(...)`.
 *
 * [baseUrl] defaults to OpenAI's public endpoint; override it (positionally or by
 * name) to point at an OpenAI-compatible gateway. Param order matches the other
 * providers (`baseUrl, apiKey, modelName`).
 */
fun ModelScope.openai(
    baseUrl: String = OPENAI_DEFAULT_BASE_URL,
    apiKey: String,
    modelName: String,
    block: OpenAIConfig.() -> Unit = {},
): ModelSelection {
    val cfg = OpenAIConfig(baseUrl, apiKey, modelName).apply(block)
    return custom(OpenAIChatModel(cfg.toConfig(), transport, cfg.params(), cfg.capabilities()))
}
