package org.koaks.provider.openai

import kotlinx.coroutines.flow.firstOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import org.koaks.framework.model.ContentPart
import org.koaks.framework.model.ItemRef
import org.koaks.framework.model.ModelCapabilities
import org.koaks.framework.model.ModelItem
import org.koaks.framework.model.ModelRequest
import org.koaks.framework.model.OutputFormat
import org.koaks.framework.model.ProviderId
import org.koaks.framework.model.Role
import org.koaks.framework.model.Support
import org.koaks.framework.provider.ChatModel
import org.koaks.framework.provider.ModelConfig
import org.koaks.framework.provider.RetryBudget
import org.koaks.framework.provider.WireDecoder
import org.koaks.framework.provider.authHeaders
import org.koaks.framework.provider.timeouts
import org.koaks.framework.transport.Framing
import org.koaks.framework.transport.HttpMethod
import org.koaks.framework.transport.ModelTransport
import org.koaks.framework.transport.WireCall
import org.koaks.framework.utils.json.JsonUtil

class OpenAIResponsesModel(
    config: ModelConfig,
    transport: ModelTransport,
    private val params: ResponsesParams = ResponsesParams(),
    override val capabilities: ModelCapabilities = DEFAULT_CAPABILITIES,
    private val codec: ResponsesCheckpointCodec = ResponsesCheckpointCodec(),
) : ChatModel(config, transport) {

    override fun newDecoder(): WireDecoder = ResponsesDecoder(
        mode = params.stateMode,
        persistCheckpoint = params.persistCheckpoint,
        basisItems = emptyList(),
        codec = codec,
    )

    override fun newDecoder(request: ModelRequest): WireDecoder = ResponsesDecoder(
        mode = params.stateMode,
        persistCheckpoint = params.persistCheckpoint,
        basisItems = request.items,
        codec = codec,
    )

    override fun toWireCall(req: ModelRequest): WireCall {
        val body = JsonUtil.toJson(toWire(req), ResponsesRequest.serializer())
        val expect = if (params.background == true) Framing.Json else Framing.Sse
        return WireCall(
            method = HttpMethod.POST,
            url = config.baseUrl,
            headers = config.authHeaders(),
            body = body,
            expect = expect,
            idempotencyKey = req.idempotencyKey,
            timeouts = config.timeouts(),
            retry = config.retry,
            rateLimit = config.rateLimit,
        )
    }

    internal fun toWire(req: ModelRequest): ResponsesRequest {
        val checkpoint = req.checkpoint
            ?.takeIf { it.providerId == ProviderId.OpenAIResponses }
            ?.let { codec.decode(it) }
        val usePrevious = checkpoint != null && params.stateMode != ResponsesStateMode.Conversation
        val inputItems = if (usePrevious) {
            req.items.drop(req.checkpoint!!.basis.itemCount.coerceAtMost(req.items.size))
        } else {
            req.items
        }
        val include = when (params.stateMode) {
            ResponsesStateMode.Replayable -> listOf("reasoning.encrypted_content")
            ResponsesStateMode.ServerStored, ResponsesStateMode.Conversation -> params.include
        }
        return ResponsesRequest(
            model = config.modelName,
            input = toInput(inputItems),
            instructions = req.instructions,
            tools = encodeTools(req),
            text = req.outputFormat.toTextFormat(),
            previousResponseId = if (usePrevious) checkpoint?.responseId else null,
            store = params.stateMode == ResponsesStateMode.ServerStored,
            stream = params.background != true,
            include = include,
            reasoning = params.reasoning,
            temperature = params.temperature,
            topP = params.topP,
            maxOutputTokens = params.maxOutputTokens,
            parallelToolCalls = when (capabilities.parallelToolCalls) {
                Support.Supported -> if (req.tools.isNotEmpty()) true else null
                Support.Unsupported -> false
                Support.Unknown -> null
            },
            truncation = params.truncation,
            background = params.background,
        )
    }

    override suspend fun abandon(responseId: String) {
        val url = "${config.baseUrl.trimEnd('/')}/$responseId/cancel"
        runCatching {
            transport.call(
                WireCall(
                    method = HttpMethod.POST,
                    url = url,
                    headers = config.authHeaders(),
                    body = "{}",
                    expect = Framing.Json,
                    timeouts = config.timeouts(),
                    retry = RetryBudget(maxRetries = 0),
                ),
            ).firstOrNull()
        }
    }

    private fun encodeTools(req: ModelRequest): List<JsonObject>? {
        val local = req.tools.map { schema ->
            buildJsonObject {
                put("type", JsonPrimitive("function"))
                put("name", JsonPrimitive(schema.name))
                put("description", JsonPrimitive(schema.description))
                put("parameters", schema.parameters)
            }
        }
        val all = local + params.serverTools
        return all.takeIf { it.isNotEmpty() }
    }
}

fun toInput(items: List<ModelItem>): JsonElement {
    if (items.isEmpty()) return buildJsonArray {}
    val calls = items.filterIsInstance<ModelItem.ToolCall>().associateBy { it.ref }
    return buildJsonArray {
        for (item in items) add(item.toInputElement(calls))
    }
}

private fun ModelItem.toInputElement(calls: Map<ItemRef, ModelItem.ToolCall>): JsonElement = when (this) {
    is ModelItem.Message -> buildJsonObject {
        put("type", JsonPrimitive(ResponsesItemTypes.MESSAGE))
        put("role", JsonPrimitive(role.wireName()))
        put(
            "content",
            buildJsonArray {
                if (content.isEmpty() && text.isNotEmpty()) {
                    add(textPart(role, text))
                } else {
                    content.filterIsInstance<ContentPart.Text>().forEach { add(textPart(role, it.text)) }
                    if (content.none { it is ContentPart.Text } && text.isNotEmpty()) add(textPart(role, text))
                }
            },
        )
        refusal?.let { put("refusal", JsonPrimitive(it)) }
    }
    is ModelItem.ToolCall -> buildJsonObject {
        put("type", JsonPrimitive(ResponsesItemTypes.FUNCTION_CALL))
        put("name", JsonPrimitive(name))
        put("arguments", JsonPrimitive(arguments))
        put("call_id", JsonPrimitive(nativeId?.raw ?: ref.value))
        nativeId?.raw?.let { put("id", JsonPrimitive(it)) }
    }
    is ModelItem.ToolResult -> buildJsonObject {
        put("type", JsonPrimitive(ResponsesItemTypes.FUNCTION_CALL_OUTPUT))
        val call = calls[callRef]
        put("call_id", JsonPrimitive(call?.nativeId?.raw ?: nativeId?.raw ?: callRef.value))
        put("output", JsonPrimitive(output))
    }
    is ModelItem.ReasoningSummary -> buildJsonObject {
        put("type", JsonPrimitive(ResponsesItemTypes.REASONING))
        put(
            "summary",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("type", JsonPrimitive("summary_text"))
                        put("text", JsonPrimitive(text))
                    },
                )
            },
        )
    }
    is ModelItem.ProviderItem -> {
        if (providerId == ProviderId.OpenAIResponses) {
            runCatching { JsonUtil.json.parseToJsonElement(payload.utf8()).jsonObject }
                .getOrElse { fallbackProviderItem() }
        } else {
            fallbackProviderItem()
        }
    }
}

private fun ModelItem.ProviderItem.fallbackProviderItem(): JsonObject = buildJsonObject {
    put("type", JsonPrimitive(kind))
    put("content", JsonPrimitive(displayText))
}

private fun textPart(role: Role, text: String): JsonObject = buildJsonObject {
    put("type", JsonPrimitive(if (role == Role.ASSISTANT) "output_text" else "input_text"))
    put("text", JsonPrimitive(text))
}

private fun Role.wireName(): String = when (this) {
    Role.SYSTEM -> "system"
    Role.USER -> "user"
    Role.ASSISTANT -> "assistant"
    Role.TOOL -> "user"
}

private fun OutputFormat.toTextFormat(): ResponsesText? = when (this) {
    OutputFormat.Text -> null
    OutputFormat.JsonObject -> ResponsesText(
        format = buildJsonObject { put("type", JsonPrimitive("json_object")) },
    )
    is OutputFormat.JsonSchema -> ResponsesText(
        format = buildJsonObject {
            put("type", JsonPrimitive("json_schema"))
            put("name", JsonPrimitive(name))
            put("schema", schema)
            put("strict", JsonPrimitive(strict))
        },
    )
}

data class ResponsesParams(
    val temperature: Double? = null,
    val topP: Double? = null,
    val maxOutputTokens: Int? = null,
    val reasoning: JsonObject? = null,
    val truncation: String? = null,
    val background: Boolean? = null,
    val include: List<String>? = null,
    val stateMode: ResponsesStateMode = ResponsesStateMode.Replayable,
    val persistCheckpoint: Boolean = false,
    val serverTools: List<JsonObject> = emptyList(),
)

val DEFAULT_RESPONSES_CAPABILITIES: ModelCapabilities = ModelCapabilities(
    parallelToolCalls = Support.Supported,
    vision = Support.Supported,
    jsonObject = Support.Supported,
    jsonSchema = Support.Supported,
    assistantPrefill = Support.Unknown,
)

private val DEFAULT_CAPABILITIES = DEFAULT_RESPONSES_CAPABILITIES

fun normalizeResponsesUrl(baseUrl: String): String {
    val trimmed = baseUrl.trim().trimEnd('/')
    return when {
        trimmed.endsWith("/responses") -> trimmed
        trimmed.endsWith("/v1") -> "$trimmed/responses"
        else -> "$trimmed/v1/responses"
    }
}
