package org.koaks.provider.ollama

import org.koaks.framework.loop.AgentDsl
import org.koaks.framework.loop.ModelScope
import org.koaks.framework.model.GenerationParams
import org.koaks.framework.model.ModelCapabilities
import org.koaks.framework.transport.ModelConfig
import org.koaks.framework.transport.StreamFormat

/**
 * Configuration scope for the Ollama provider DSL: `model { ollama(...) { ... } }`.
 * Only fields differing from the [ModelCapabilities] defaults need to be set.
 */
@AgentDsl
class OllamaConfig(
    var baseUrl: String,
    var modelName: String,
    var apiKey: String = "ollama",
) {
    var temperature: Double? = null
    var maxTokens: Int? = null
    var topP: Double? = null

    private var caps = ModelCapabilities(parallelToolCalls = false)
    fun capabilities(block: OllamaCapabilitiesScope.() -> Unit) {
        caps = OllamaCapabilitiesScope(caps).apply(block).build()
    }

    internal fun toConfig(): ModelConfig = ModelConfig(
        baseUrl = baseUrl,
        apiKey = apiKey,
        modelName = modelName,
        defaultParams = GenerationParams(temperature = temperature, maxTokens = maxTokens, topP = topP),
        streamFormat = StreamFormat.NDJSON,
    )

    internal fun capabilities(): ModelCapabilities = caps
}

@AgentDsl
class OllamaCapabilitiesScope(initial: ModelCapabilities) {
    var streaming: Boolean = initial.streaming
    var tools: Boolean = initial.tools
    var parallelToolCalls: Boolean = initial.parallelToolCalls
    var vision: Boolean = initial.vision
    var jsonMode: Boolean = initial.jsonMode

    internal fun build() = ModelCapabilities(streaming, tools, parallelToolCalls, vision, jsonMode)
}

/**
 * Selects Ollama as the agent's model. Builds an [OllamaChatModel] using the
 * transport from [ModelScope] (agent-owned unless externally injected).
 */
fun ModelScope.ollama(
    baseUrl: String,
    modelName: String,
    apiKey: String = "ollama",
    block: OllamaConfig.() -> Unit = {},
) {
    val cfg = OllamaConfig(baseUrl, modelName, apiKey).apply(block)
    selected = OllamaChatModel(cfg.toConfig(), transport, cfg.capabilities())
}
