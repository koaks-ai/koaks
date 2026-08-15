package org.koaks.provider.ollama

import org.koaks.framework.loop.AgentDSL
import org.koaks.framework.loop.ModelScope
import org.koaks.framework.loop.ModelSelection
import org.koaks.framework.model.ModelCapabilities
import org.koaks.framework.model.Support
import org.koaks.framework.provider.DEFAULT_STREAM_IDLE_TIMEOUT_MS
import org.koaks.framework.provider.ModelConfig

@AgentDSL
class OllamaConfig(
    var baseUrl: String,
    var apiKey: String = "ollama",
    var modelName: String,
) {
    var temperature: Double? = null
    var topP: Double? = null
    var maxTokens: Int? = null
    var stop: List<String>? = null
    var think: Boolean? = null
    var streamIdleTimeoutMs: Long = DEFAULT_STREAM_IDLE_TIMEOUT_MS

    private var caps = ModelCapabilities(parallelToolCalls = Support.Unsupported)
    fun capabilities(block: OllamaCapabilitiesScope.() -> Unit) {
        caps = OllamaCapabilitiesScope(caps).apply(block).build()
    }

    internal fun toConfig(): ModelConfig = ModelConfig(
        baseUrl = baseUrl,
        apiKey = apiKey,
        modelName = modelName,
        streamIdleTimeoutMs = streamIdleTimeoutMs,
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
    var parallelToolCalls: Boolean = initial.parallelToolCalls == Support.Supported
    var vision: Boolean = initial.vision == Support.Supported
    var jsonMode: Boolean = initial.jsonObject == Support.Supported

    internal fun build() = ModelCapabilities(
        parallelToolCalls = if (parallelToolCalls) Support.Supported else Support.Unsupported,
        vision = if (vision) Support.Supported else Support.Unknown,
        jsonObject = if (jsonMode) Support.Supported else Support.Unknown,
    )
}

fun ModelScope.ollama(
    baseUrl: String,
    apiKey: String = "ollama",
    modelName: String,
    block: OllamaConfig.() -> Unit = {},
): ModelSelection {
    val cfg = OllamaConfig(baseUrl, apiKey, modelName).apply(block)
    return custom(OllamaChatModel(cfg.toConfig(), transport, cfg.params(), cfg.capabilities()))
}
