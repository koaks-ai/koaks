package org.koaks.provider.qwen

import org.koaks.framework.loop.AgentDSL
import org.koaks.framework.loop.ModelScope
import org.koaks.framework.loop.ModelSelection
import org.koaks.framework.model.ModelCapabilities
import org.koaks.framework.provider.ModelConfig

/**
 * Configuration scope for the Qwen provider DSL: `model { qwen(...) { ... } }`.
 * Generation params are Qwen-native and set flat on this block; only fields that
 * differ from defaults need to be set.
 */
@AgentDSL
class QwenConfig(
    var baseUrl: String,
    var apiKey: String,
    var modelName: String,
) {
    // Qwen-native generation params, bound to this model.
    var temperature: Double? = null
    var maxTokens: Int? = null
    var topP: Double? = null
    var stop: List<String>? = null
    var presencePenalty: Double? = null
    var frequencyPenalty: Double? = null

    /** Maps to Qwen's `enable_thinking`; surfaced as `ReasoningDelta` events. */
    var enableThinking: Boolean? = null

    private var caps = ModelCapabilities()
    fun capabilities(block: CapabilitiesScope.() -> Unit) {
        caps = CapabilitiesScope(caps).apply(block).build()
    }

    internal fun toConfig(): ModelConfig = ModelConfig(
        baseUrl = baseUrl,
        apiKey = apiKey,
        modelName = modelName,
    )

    internal fun params(): QwenParams = QwenParams(
        temperature = temperature,
        maxTokens = maxTokens,
        topP = topP,
        stop = stop,
        presencePenalty = presencePenalty,
        frequencyPenalty = frequencyPenalty,
        enableThinking = enableThinking,
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
    return custom(QwenChatModel(cfg.toConfig(), transport, cfg.params(), cfg.capabilities()))
}
