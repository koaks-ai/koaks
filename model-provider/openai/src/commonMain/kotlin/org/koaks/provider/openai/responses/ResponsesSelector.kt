package org.koaks.provider.openai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.koaks.framework.loop.AgentDSL
import org.koaks.framework.loop.ModelScope
import org.koaks.framework.loop.ModelSelection
import org.koaks.framework.model.ModelCapabilities
import org.koaks.framework.model.Support
import org.koaks.framework.provider.DEFAULT_STREAM_IDLE_TIMEOUT_MS
import org.koaks.framework.provider.ModelConfig

@AgentDSL
class OpenAIResponsesConfig(
    var baseUrl: String,
    var apiKey: String,
    var modelName: String,
) {
    var temperature: Double? = null
    var topP: Double? = null
    var maxOutputTokens: Int? = null
    var reasoning: JsonObject? = null
    var truncation: String? = null
    var background: Boolean? = null
    var backgroundPollIntervalMs: Long = 2_000
    var streamIdleTimeoutMs: Long = DEFAULT_STREAM_IDLE_TIMEOUT_MS
    var stateMode: ResponsesStateMode = ResponsesStateMode.Replayable
    var persistCheckpoint: Boolean = false
    var include: List<String>? = null

    private val serverTools = mutableListOf<JsonObject>()
    private var caps = DEFAULT_RESPONSES_CAPABILITIES

    fun webSearch(searchContextSize: String? = null) {
        serverTools += buildJsonObject {
            put("type", JsonPrimitive("web_search"))
            searchContextSize?.let { put("search_context_size", JsonPrimitive(it)) }
        }
    }

    fun fileSearch(vararg vectorStoreIds: String) {
        serverTools += buildJsonObject {
            put("type", JsonPrimitive("file_search"))
            put("vector_store_ids", buildJsonArray { vectorStoreIds.forEach { add(JsonPrimitive(it)) } })
        }
    }

    fun codeInterpreter(container: String? = null) {
        serverTools += buildJsonObject {
            put("type", JsonPrimitive("code_interpreter"))
            container?.let {
                put("container", buildJsonObject { put("type", JsonPrimitive(it)) })
            }
        }
    }

    fun capabilities(block: OpenAIResponsesCapabilitiesScope.() -> Unit) {
        caps = OpenAIResponsesCapabilitiesScope(caps).apply(block).build()
    }

    internal fun toConfig(): ModelConfig = ModelConfig(
        baseUrl = normalizeResponsesUrl(baseUrl),
        apiKey = apiKey,
        modelName = modelName,
        streamIdleTimeoutMs = streamIdleTimeoutMs,
    )

    internal fun params(): ResponsesParams = ResponsesParams(
        temperature = temperature,
        topP = topP,
        maxOutputTokens = maxOutputTokens,
        reasoning = reasoning,
        truncation = truncation,
        background = background,
        backgroundPollIntervalMs = backgroundPollIntervalMs,
        include = include,
        stateMode = stateMode,
        persistCheckpoint = persistCheckpoint,
        serverTools = serverTools.toList(),
    )

    internal fun capabilities(): ModelCapabilities = caps
}

@AgentDSL
class OpenAIResponsesCapabilitiesScope(initial: ModelCapabilities) {
    var parallelToolCalls: Boolean = initial.parallelToolCalls == Support.Supported
    var vision: Boolean = initial.vision == Support.Supported
    var jsonMode: Boolean = initial.jsonObject == Support.Supported
    var jsonSchema: Boolean = initial.jsonSchema == Support.Supported

    internal fun build() = ModelCapabilities(
        parallelToolCalls = if (parallelToolCalls) Support.Supported else Support.Unsupported,
        vision = if (vision) Support.Supported else Support.Unknown,
        jsonObject = if (jsonMode) Support.Supported else Support.Unknown,
        jsonSchema = if (jsonSchema) Support.Supported else Support.Unknown,
        assistantPrefill = Support.Unknown,
    )
}

fun ModelScope.openaiResponses(
    baseUrl: String = OPENAI_DEFAULT_BASE_URL,
    apiKey: String,
    modelName: String,
    block: OpenAIResponsesConfig.() -> Unit = {},
): ModelSelection {
    val cfg = OpenAIResponsesConfig(baseUrl, apiKey, modelName).apply(block)
    return custom(OpenAIResponsesModel(cfg.toConfig(), transport, cfg.params(), cfg.capabilities()))
}
