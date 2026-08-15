package org.koaks.provider.openai.responses

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koaks.framework.model.Annotation
import org.koaks.framework.model.ContentPart
import org.koaks.framework.model.ItemRef
import org.koaks.framework.model.ModelCapabilities
import org.koaks.framework.model.ModelItem
import org.koaks.framework.model.ModelRequest
import org.koaks.framework.model.OutputFormat
import org.koaks.framework.model.ProviderId
import org.koaks.framework.model.Role
import org.koaks.framework.model.Support
import org.koaks.framework.model.rawFor
import org.koaks.framework.provider.ChatModel
import org.koaks.framework.provider.ModelConfig
import org.koaks.framework.provider.RetryBudget
import org.koaks.framework.provider.WireDecoder
import org.koaks.framework.provider.authHeaders
import org.koaks.framework.provider.timeouts
import org.koaks.framework.transport.Framing
import org.koaks.framework.transport.HttpMethod
import org.koaks.framework.transport.ModelTransport
import org.koaks.framework.transport.TransportException
import org.koaks.framework.transport.WireCall
import org.koaks.framework.transport.WireFrame
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

    override fun wireFrames(request: ModelRequest): Flow<WireFrame> {
        if (params.background != true) return super.wireFrames(request)
        return flow {
            val completed = withTimeoutOrNull(config.requestTimeoutMs) {
                var call = toWireCall(request)
                while (true) {
                    val frame = transport.call(call).firstOrNull()
                        ?: throw TransportException("background response returned no frame")
                    emit(frame)
                    if (frame is WireFrame.HttpError) return@withTimeoutOrNull
                    val body = frame as? WireFrame.Body
                        ?: throw TransportException("background response expected a JSON body")
                    val response = runCatching {
                        JsonUtil.json.parseToJsonElement(body.text).jsonObject
                    }.getOrElse { throw TransportException("invalid background response JSON", cause = it) }
                    val id = response["id"]?.jsonPrimitive?.content
                        ?: throw TransportException("background response is missing id")
                    when (val status = response["status"]?.jsonPrimitive?.content) {
                        "queued", "in_progress" -> {
                            delay(params.backgroundPollIntervalMs)
                            call = retrieveCall(id)
                        }
                        "completed", "incomplete", "failed", "cancelled" -> return@withTimeoutOrNull
                        else -> throw TransportException("unknown background response status: $status")
                    }
                }
            }
            if (completed == null) {
                throw TransportException("background response did not finish within ${config.requestTimeoutMs}ms")
            }
        }
    }

    private fun retrieveCall(responseId: String): WireCall = WireCall(
        method = HttpMethod.GET,
        url = "${config.baseUrl.trimEnd('/')}/$responseId",
        headers = config.authHeaders(),
        expect = Framing.Json,
        timeouts = config.timeouts(),
        retry = config.retry,
        rateLimit = config.rateLimit,
    )

    internal fun toWire(req: ModelRequest): ResponsesRequest {
        val checkpoint = req.checkpoint
            ?.takeIf { it.providerId == ProviderId.OpenAIResponses }
            ?.let { codec.decode(it) }
        val chainStored = params.stateMode == ResponsesStateMode.ServerStored && checkpoint != null
        val inputItems = if (chainStored) {
            req.items.drop(req.checkpoint!!.basis.itemCount.coerceAtMost(req.items.size))
        } else {
            req.items
        }
        val include = when (params.stateMode) {
            ResponsesStateMode.Replayable ->
                (params.include.orEmpty() + "reasoning.encrypted_content").distinct()
            ResponsesStateMode.ServerStored, ResponsesStateMode.Conversation -> params.include
        }
        return ResponsesRequest(
            model = config.modelName,
            input = toInput(inputItems),
            instructions = req.instructions,
            tools = encodeTools(req),
            text = req.outputFormat.toTextFormat(),
            previousResponseId = if (chainStored) checkpoint?.responseId else null,
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
                val texts = content.filterIsInstance<ContentPart.Text>().map { it.text }
                    .ifEmpty { text.takeIf { it.isNotEmpty() }?.let(::listOf).orEmpty() }
                texts.forEachIndexed { index, value ->
                    add(textPart(role, value, if (index == 0) annotations else emptyList()))
                }
                refusal?.let { value ->
                    add(
                        buildJsonObject {
                            put("type", JsonPrimitive("refusal"))
                            put("refusal", JsonPrimitive(value))
                        },
                    )
                }
            },
        )
    }
    is ModelItem.ToolCall -> buildJsonObject {
        val callId = wireCallId()
        put("type", JsonPrimitive(ResponsesItemTypes.FUNCTION_CALL))
        put("name", JsonPrimitive(name))
        put("arguments", JsonPrimitive(arguments))
        put("call_id", JsonPrimitive(callId))
        nativeItemId.rawFor(ProviderId.OpenAIResponses)?.let { put("id", JsonPrimitive(it)) }
    }
    is ModelItem.ToolResult -> buildJsonObject {
        val call = calls[callRef]
        val callId = call?.wireCallId() ?: callRef.value
        put("type", JsonPrimitive(ResponsesItemTypes.FUNCTION_CALL_OUTPUT))
        put("call_id", JsonPrimitive(callId))
        put("output", JsonPrimitive(output))
        nativeId.rawFor(ProviderId.OpenAIResponses)?.let { put("id", JsonPrimitive(it)) }
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

private fun ModelItem.ToolCall.wireCallId(): String =
    nativeId.rawFor(ProviderId.OpenAIResponses) ?: ref.value

private fun textPart(role: Role, text: String, annotations: List<Annotation>): JsonObject = buildJsonObject {
    put("type", JsonPrimitive(if (role == Role.ASSISTANT) "output_text" else "input_text"))
    put("text", JsonPrimitive(text))
    if (role == Role.ASSISTANT && annotations.isNotEmpty()) {
        put("annotations", buildJsonArray { annotations.forEach { add(it.toJson()) } })
    }
}

private fun Annotation.toJson(): JsonObject = when (this) {
    is Annotation.UrlCitation -> buildJsonObject {
        put("type", JsonPrimitive("url_citation"))
        put("url", JsonPrimitive(url))
        title?.let { put("title", JsonPrimitive(it)) }
        startIndex?.let { put("start_index", JsonPrimitive(it)) }
        endIndex?.let { put("end_index", JsonPrimitive(it)) }
    }
    is Annotation.FileCitation -> buildJsonObject {
        put("type", JsonPrimitive("file_citation"))
        put("file_id", JsonPrimitive(fileId))
        filename?.let { put("filename", JsonPrimitive(it)) }
        startIndex?.let { put("start_index", JsonPrimitive(it)) }
        endIndex?.let { put("end_index", JsonPrimitive(it)) }
    }
    is Annotation.Generic -> runCatching {
        JsonUtil.json.parseToJsonElement(payload).jsonObject
    }.getOrElse {
        buildJsonObject {
            put("type", JsonPrimitive(kind))
            put("payload", JsonPrimitive(payload))
        }
    }
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
    val backgroundPollIntervalMs: Long = 2_000,
    val include: List<String>? = null,
    val stateMode: ResponsesStateMode = ResponsesStateMode.Replayable,
    val persistCheckpoint: Boolean = false,
    val serverTools: List<JsonObject> = emptyList(),
) {
    init {
        require(backgroundPollIntervalMs > 0) { "backgroundPollIntervalMs must be positive" }
    }
}

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
