package org.koaks.provider.qwen

import org.koaks.framework.loop.AgentDSL
import org.koaks.framework.loop.ModelScope
import org.koaks.framework.loop.ModelSelection
import org.koaks.framework.model.GenerationParams
import org.koaks.framework.model.GenerationParamsScope
import org.koaks.framework.model.ModelCapabilities
import org.koaks.framework.model.generationParams
import org.koaks.framework.provider.ModelConfig

/**
 * Configuration scope for the Qwen provider DSL: `model { qwen(...) { ... } }`.
 * Only fields that differ from the [ModelCapabilities] defaults need to be set.
 */
@AgentDSL
class QwenConfig(
    var baseUrl: String,
    var apiKey: String,
    var modelName: String,
) {
    private var params = GenerationParams()

    /**
     * The model's default generation params: `params { temperature = 0.7 }`. These
     * are overridden per request by the agent's own params (see [GenerationParams.over]).
     */
    fun params(block: GenerationParamsScope.() -> Unit) {
        params = generationParams(block)
    }

    private var caps = ModelCapabilities()
    fun capabilities(block: CapabilitiesScope.() -> Unit) {
        caps = CapabilitiesScope(caps).apply(block).build()
    }

    internal fun toConfig(): ModelConfig = ModelConfig(
        baseUrl = baseUrl,
        apiKey = apiKey,
        modelName = modelName,
        defaultParams = params,
    )

    internal fun capabilities(): ModelCapabilities = caps
}

@AgentDSL
class CapabilitiesScope(initial: ModelCapabilities) {
    var parallelToolCalls: Boolean = initial.parallelToolCalls
    var vision: Boolean = initial.vision
    var jsonMode: Boolean = initial.jsonMode

    internal fun build() = ModelCapabilities(parallelToolCalls, vision, jsonMode)
}

/**
 * Selects Qwen as the agent's model. The provider builds a [QwenChatModel] using the
 * transport from [ModelScope] (agent-owned unless externally injected), returning it
 * as a [ModelSelection] so callers can chain `.fallback(...)`.
 */
fun ModelScope.qwen(
    baseUrl: String,
    apiKey: String,
    modelName: String,
    block: QwenConfig.() -> Unit = {},
): ModelSelection {
    val cfg = QwenConfig(baseUrl, apiKey, modelName).apply(block)
    return custom(QwenChatModel(cfg.toConfig(), transport, cfg.capabilities()))
}
