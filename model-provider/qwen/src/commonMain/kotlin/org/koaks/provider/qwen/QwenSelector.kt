package org.koaks.provider.qwen

import org.koaks.framework.loop.AgentDSL
import org.koaks.framework.loop.ModelScope
import org.koaks.framework.loop.ModelSelection
import org.koaks.framework.model.ModelCapabilities
import org.koaks.framework.model.Support
import org.koaks.framework.provider.DEFAULT_STREAM_IDLE_TIMEOUT_MS
import org.koaks.framework.provider.ModelConfig
import org.koaks.provider.chatcompletions.normalizeChatCompletionsUrl

const val QWEN_DEFAULT_BASE_URL: String = "https://dashscope.aliyuncs.com/compatible-mode"

@AgentDSL
class QwenConfig(
    var baseUrl: String,
    var apiKey: String,
    var modelName: String,
) {
    var temperature: Double? = null
    var maxTokens: Int? = null
    var topP: Double? = null
    var stop: List<String>? = null
    var presencePenalty: Double? = null
    var frequencyPenalty: Double? = null
    var enableThinking: Boolean? = null
    var streamIdleTimeoutMs: Long = DEFAULT_STREAM_IDLE_TIMEOUT_MS

    private var caps = ModelCapabilities()
    fun capabilities(block: CapabilitiesScope.() -> Unit) {
        caps = CapabilitiesScope(caps).apply(block).build()
    }

    internal fun toConfig(): ModelConfig = ModelConfig(
        baseUrl = normalizeChatCompletionsUrl(baseUrl),
        apiKey = apiKey,
        modelName = modelName,
        streamIdleTimeoutMs = streamIdleTimeoutMs,
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
    var parallelToolCalls: Boolean = initial.parallelToolCalls == Support.Supported
    var vision: Boolean = initial.vision == Support.Supported
    var jsonMode: Boolean = initial.jsonObject == Support.Supported

    internal fun build() = ModelCapabilities(
        parallelToolCalls = if (parallelToolCalls) Support.Supported else Support.Unsupported,
        vision = if (vision) Support.Supported else Support.Unknown,
        jsonObject = if (jsonMode) Support.Supported else Support.Unknown,
    )
}

fun ModelScope.qwen(
    baseUrl: String = QWEN_DEFAULT_BASE_URL,
    apiKey: String,
    modelName: String,
    block: QwenConfig.() -> Unit = {},
): ModelSelection {
    val cfg = QwenConfig(baseUrl, apiKey, modelName).apply(block)
    return custom(QwenChatModel(cfg.toConfig(), transport, cfg.params(), cfg.capabilities()))
}
