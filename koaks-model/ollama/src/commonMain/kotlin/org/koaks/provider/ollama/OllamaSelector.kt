package org.koaks.provider.ollama

import org.koaks.framework.loop.AgentDSL
import org.koaks.framework.loop.ModelScope
import org.koaks.framework.loop.ModelSelection
import org.koaks.framework.model.ModelCapabilities
import org.koaks.framework.provider.ModelConfig
import org.koaks.framework.provider.StreamFormat

/**
 * Configuration scope for the Ollama provider DSL: `model { ollama(...) { ... } }`.
 * Generation params are Ollama-native and set flat on this block; only fields that
 * differ from defaults need to be set.
 */
@AgentDSL
class OllamaConfig(
    var baseUrl: String,
    var apiKey: String = "ollama",
    var modelName: String,
) {
    // Ollama-native generation params, bound to this model.
    var temperature: Double? = null
    var topP: Double? = null

    /** Maps to Ollama's `num_predict`. */
    var maxTokens: Int? = null
    var stop: List<String>? = null

    /** Maps to Ollama's `think`; surfaced as `ReasoningDelta` events. */
    var think: Boolean? = null

    private var caps = ModelCapabilities(parallelToolCalls = false)
    fun capabilities(block: OllamaCapabilitiesScope.() -> Unit) {
        caps = OllamaCapabilitiesScope(caps).apply(block).build()
    }

    internal fun toConfig(): ModelConfig = ModelConfig(
        baseUrl = baseUrl,
        apiKey = apiKey,
        modelName = modelName,
        streamFormat = StreamFormat.NDJSON,
    )

    internal fun params(): OllamaParams = OllamaParams(
        temperature = temperature,
        topP = topP,
        maxTokens = maxTokens,
        stop = stop,
        think = think,
    )

    internal fun capabilities(): ModelCapabilities = caps
}

@AgentDSL
class OllamaCapabilitiesScope(initial: ModelCapabilities) {
    var parallelToolCalls: Boolean = initial.parallelToolCalls
    var vision: Boolean = initial.vision
    var jsonMode: Boolean = initial.jsonMode

    internal fun build() = ModelCapabilities(parallelToolCalls, vision, jsonMode)
}

/**
 * Selects Ollama as the agent's model. Builds an [OllamaChatModel] using the
 * transport from [ModelScope] (agent-owned unless externally injected), returning it
 * as a [ModelSelection] so callers can chain `.fallback(...)`.
 */
fun ModelScope.ollama(
    baseUrl: String,
    apiKey: String = "ollama",
    modelName: String,
    block: OllamaConfig.() -> Unit = {},
): ModelSelection {
    val cfg = OllamaConfig(baseUrl, apiKey, modelName).apply(block)
    return custom(OllamaChatModel(cfg.toConfig(), transport, cfg.params(), cfg.capabilities()))
}
