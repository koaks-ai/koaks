package org.koaks.provider.anthropic

import org.koaks.framework.loop.AgentDSL
import org.koaks.framework.loop.ModelScope
import org.koaks.framework.loop.ModelSelection
import org.koaks.framework.model.ModelCapabilities
import org.koaks.framework.provider.AuthScheme
import org.koaks.framework.provider.ModelConfig

/** Anthropic's default Messages API endpoint. */
const val ANTHROPIC_DEFAULT_BASE_URL: String = "https://api.anthropic.com/v1/messages"

/** The `anthropic-version` header value pinned by default. */
const val ANTHROPIC_DEFAULT_VERSION: String = "2023-06-01"

/**
 * Configuration scope for the Anthropic provider DSL: `model { anthropic(...) { ... } }`.
 * Generation params are Anthropic-native and set flat on this block; only fields that
 * differ from defaults need to be set.
 */
@AgentDSL
class AnthropicConfig(
    var baseUrl: String,
    var apiKey: String,
    var modelName: String,
) {
    /** Maps to Anthropic's required `max_tokens`. */
    var maxTokens: Int = 4096

    /**
     * Anthropic-native sampling params, bound to this model. Note: `temperature` /
     * `topP` / `topK` are rejected (HTTP 400) by Opus 4.7+ / Fable thinking-only
     * models — leave them unset there; they are safe on Sonnet/Haiku.
     */
    var temperature: Double? = null
    var topP: Double? = null
    var topK: Int? = null
    var stopSequences: List<String>? = null

    /** The `anthropic-version` header sent with every request. */
    var anthropicVersion: String = ANTHROPIC_DEFAULT_VERSION

    private var caps = ModelCapabilities(jsonMode = false)
    fun capabilities(block: AnthropicCapabilitiesScope.() -> Unit) {
        caps = AnthropicCapabilitiesScope(caps).apply(block).build()
    }

    internal fun toConfig(): ModelConfig = ModelConfig(
        baseUrl = normalizeAnthropicMessagesUrl(baseUrl),
        apiKey = apiKey,
        modelName = modelName,
        auth = AuthScheme.Header("x-api-key"),
        customHeaders = mapOf("anthropic-version" to anthropicVersion),
    )

    internal fun params(): AnthropicParams = AnthropicParams(
        maxTokens = maxTokens,
        temperature = temperature,
        topP = topP,
        topK = topK,
        stopSequences = stopSequences,
    )

    internal fun capabilities(): ModelCapabilities = caps
}

@AgentDSL
class AnthropicCapabilitiesScope(initial: ModelCapabilities) {
    var parallelToolCalls: Boolean = initial.parallelToolCalls
    var vision: Boolean = initial.vision

    /** Anthropic has no `response_format: json_object`; defaults false. */
    var jsonMode: Boolean = initial.jsonMode

    internal fun build() = ModelCapabilities(parallelToolCalls, vision, jsonMode)
}

/**
 * Selects Anthropic (Messages API) as the agent's model. Builds an [AnthropicChatModel]
 * using the transport from [ModelScope] (agent-owned unless externally injected),
 * returning it as a [ModelSelection] so callers can chain `.fallback(...)`.
 *
 * [baseUrl] accepts either the full Messages endpoint (`.../v1/messages`) or an
 * SDK-style provider base URL, such as DeepSeek's `https://api.deepseek.com/anthropic`.
 * Param order matches the other providers (`baseUrl, apiKey, modelName`).
 */
fun ModelScope.anthropic(
    baseUrl: String = ANTHROPIC_DEFAULT_BASE_URL,
    apiKey: String,
    modelName: String,
    block: AnthropicConfig.() -> Unit = {},
): ModelSelection {
    val cfg = AnthropicConfig(baseUrl, apiKey, modelName).apply(block)
    return custom(AnthropicChatModel(cfg.toConfig(), transport, cfg.params(), cfg.capabilities()))
}

private fun normalizeAnthropicMessagesUrl(baseUrl: String): String {
    val trimmed = baseUrl.trim().trimEnd('/')
    return if (trimmed.endsWith("/messages")) trimmed else "$trimmed/v1/messages"
}
