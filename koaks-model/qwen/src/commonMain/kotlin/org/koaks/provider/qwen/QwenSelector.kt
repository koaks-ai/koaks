package org.koaks.provider.qwen

import org.koaks.framework.loop.AgentDsl
import org.koaks.framework.loop.ModelScope
import org.koaks.framework.model.GenerationParams
import org.koaks.framework.model.ModelCapabilities
import org.koaks.framework.transport.ModelConfig

/**
 * Configuration scope for the Qwen provider DSL: `model { qwen(...) { ... } }`.
 * Only fields that differ from the [ModelCapabilities] defaults need to be set.
 */
@AgentDsl
class QwenConfig(
    var baseUrl: String,
    var apiKey: String,
    var modelName: String,
) {
    var temperature: Double? = null
    var maxTokens: Int? = null
    var topP: Double? = null

    private var caps = ModelCapabilities()
    fun capabilities(block: CapabilitiesScope.() -> Unit) {
        caps = CapabilitiesScope(caps).apply(block).build()
    }

    internal fun toConfig(): ModelConfig = ModelConfig(
        baseUrl = baseUrl,
        apiKey = apiKey,
        modelName = modelName,
        defaultParams = GenerationParams(temperature = temperature, maxTokens = maxTokens, topP = topP),
    )

    internal fun capabilities(): ModelCapabilities = caps
}

@AgentDsl
class CapabilitiesScope(initial: ModelCapabilities) {
    var streaming: Boolean = initial.streaming
    var tools: Boolean = initial.tools
    var parallelToolCalls: Boolean = initial.parallelToolCalls
    var vision: Boolean = initial.vision
    var jsonMode: Boolean = initial.jsonMode

    internal fun build() = ModelCapabilities(streaming, tools, parallelToolCalls, vision, jsonMode)
}

/**
 * Selects Qwen as the agent's model. The provider builds a [QwenChatModel] using the
 * transport from [ModelScope] (agent-owned unless externally injected).
 */
fun ModelScope.qwen(
    baseUrl: String,
    apiKey: String,
    modelName: String,
    block: QwenConfig.() -> Unit = {},
) {
    val cfg = QwenConfig(baseUrl, apiKey, modelName).apply(block)
    selected = QwenChatModel(cfg.toConfig(), transport, cfg.capabilities())
}
