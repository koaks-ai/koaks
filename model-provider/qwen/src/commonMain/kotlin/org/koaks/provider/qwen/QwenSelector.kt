package org.koaks.provider.qwen

import org.koaks.framework.loop.AgentDSL
import org.koaks.framework.loop.ModelScope
import org.koaks.framework.loop.ModelSelection
import org.koaks.framework.model.ModelCapabilities
import org.koaks.framework.provider.ModelConfig
import org.koaks.framework.provider.DEFAULT_STREAM_IDLE_TIMEOUT_MS

/** Qwen's OpenAI-compatible API base URL. */
const val QWEN_DEFAULT_BASE_URL: String = "https://dashscope.aliyuncs.com/compatible-mode"

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

    /** Maximum silence between SSE lines before the request fails. */
    var streamIdleTimeoutMs: Long = DEFAULT_STREAM_IDLE_TIMEOUT_MS

    /** Require the standard `data: [DONE]` terminator; disable for non-standard gateways. */
    var requireStreamEndMarker: Boolean = true

    private var caps = ModelCapabilities()
    fun capabilities(block: CapabilitiesScope.() -> Unit) {
        caps = CapabilitiesScope(caps).apply(block).build()
    }

    internal fun toConfig(): ModelConfig = ModelConfig(
        baseUrl = normalizeChatCompletionsUrl(baseUrl),
        apiKey = apiKey,
        modelName = modelName,
        streamIdleTimeoutMs = streamIdleTimeoutMs,
        requireStreamEndMarker = requireStreamEndMarker,
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
 *
 * [baseUrl] accepts a provider base URL (`https://dashscope.aliyuncs.com/compatible-mode`),
 * an SDK-style base URL (`.../v1`), or the full Chat Completions endpoint
 * (`.../v1/chat/completions`).
 */
fun ModelScope.qwen(
    baseUrl: String = QWEN_DEFAULT_BASE_URL,
    apiKey: String,
    modelName: String,
    block: QwenConfig.() -> Unit = {},
): ModelSelection {
    val cfg = QwenConfig(baseUrl, apiKey, modelName).apply(block)
    return custom(QwenChatModel(cfg.toConfig(), transport, cfg.params(), cfg.capabilities()))
}

private fun normalizeChatCompletionsUrl(baseUrl: String): String {
    val trimmed = baseUrl.trim().trimEnd('/')
    return when {
        trimmed.endsWith("/chat/completions") -> trimmed
        trimmed.endsWith("/v1") -> "$trimmed/chat/completions"
        else -> "$trimmed/v1/chat/completions"
    }
}
