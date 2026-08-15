package org.koaks.provider.openai

import org.koaks.framework.loop.AgentDSL
import org.koaks.framework.loop.ModelScope
import org.koaks.framework.loop.ModelSelection
import org.koaks.framework.model.ModelCapabilities
import org.koaks.framework.model.Support
import org.koaks.framework.provider.DEFAULT_STREAM_IDLE_TIMEOUT_MS
import org.koaks.framework.provider.ModelConfig
import org.koaks.provider.chatcompletions.normalizeChatCompletionsUrl

const val OPENAI_DEFAULT_BASE_URL: String = "https://api.openai.com"

@AgentDSL
class OpenAIConfig(
    var baseUrl: String,
    var apiKey: String,
    var modelName: String,
) {
    var temperature: Double? = null
    var maxCompletionTokens: Int? = null
    var topP: Double? = null
    var stop: List<String>? = null
    var presencePenalty: Double? = null
    var frequencyPenalty: Double? = null
    var reasoningEffort: String? = null
    var streamIdleTimeoutMs: Long = DEFAULT_STREAM_IDLE_TIMEOUT_MS

    private var caps = ModelCapabilities()
    fun capabilities(block: OpenAICapabilitiesScope.() -> Unit) {
        caps = OpenAICapabilitiesScope(caps).apply(block).build()
    }

    internal fun toConfig(): ModelConfig = ModelConfig(
        baseUrl = normalizeChatCompletionsUrl(baseUrl),
        apiKey = apiKey,
        modelName = modelName,
        streamIdleTimeoutMs = streamIdleTimeoutMs,
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
    var parallelToolCalls: Boolean = initial.parallelToolCalls == Support.Supported
    var vision: Boolean = initial.vision == Support.Supported
    var jsonMode: Boolean = initial.jsonObject == Support.Supported
    var jsonSchema: Boolean = initial.jsonSchema == Support.Supported

    internal fun build() = ModelCapabilities(
        parallelToolCalls = if (parallelToolCalls) Support.Supported else Support.Unsupported,
        vision = if (vision) Support.Supported else Support.Unknown,
        jsonObject = if (jsonMode) Support.Supported else Support.Unknown,
        jsonSchema = if (jsonSchema) Support.Supported else Support.Unknown,
    )
}

fun ModelScope.openai(
    baseUrl: String = OPENAI_DEFAULT_BASE_URL,
    apiKey: String,
    modelName: String,
    block: OpenAIConfig.() -> Unit = {},
): ModelSelection {
    val cfg = OpenAIConfig(baseUrl, apiKey, modelName).apply(block)
    return custom(OpenAIChatModel(cfg.toConfig(), transport, cfg.params(), cfg.capabilities()))
}
