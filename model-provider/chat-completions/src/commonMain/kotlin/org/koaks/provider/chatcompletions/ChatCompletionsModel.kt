package org.koaks.provider.chatcompletions

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.koaks.framework.model.ModelCapabilities
import org.koaks.framework.model.ModelItem
import org.koaks.framework.model.ModelRequest
import org.koaks.framework.model.OutputFormat
import org.koaks.framework.model.ProviderId
import org.koaks.framework.model.Role
import org.koaks.framework.model.Support
import org.koaks.framework.model.ensureDroppable
import org.koaks.framework.provider.ChatModel
import org.koaks.framework.provider.ModelConfig
import org.koaks.framework.provider.WireDecoder
import org.koaks.framework.provider.authHeaders
import org.koaks.framework.provider.timeouts
import org.koaks.framework.tool.ToolSchema
import org.koaks.framework.transport.Framing
import org.koaks.framework.transport.HttpMethod
import org.koaks.framework.transport.ModelTransport
import org.koaks.framework.transport.WireCall
import org.koaks.framework.utils.json.JsonUtil

data class ChatCompletionsParams(
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val maxCompletionTokens: Int? = null,
    val topP: Double? = null,
    val stop: List<String>? = null,
    val presencePenalty: Double? = null,
    val frequencyPenalty: Double? = null,
    val reasoningEffort: String? = null,
    val enableThinking: Boolean? = null,
)

open class ChatCompletionsModel(
    config: ModelConfig,
    transport: ModelTransport,
    private val providerId: ProviderId,
    private val params: ChatCompletionsParams = ChatCompletionsParams(),
    override val capabilities: ModelCapabilities = ModelCapabilities(),
) : ChatModel(config, transport) {

    override fun newDecoder(): WireDecoder = ChatCompletionsDecoder(providerId)

    override fun toWireCall(req: ModelRequest): WireCall {
        val body = JsonUtil.toJson(toWire(req), ChatCompletionsRequest.serializer())
        return WireCall(
            method = HttpMethod.POST,
            url = config.baseUrl,
            headers = config.authHeaders(),
            body = body,
            expect = Framing.Sse,
            idempotencyKey = req.idempotencyKey,
            timeouts = config.timeouts(),
            retry = config.retry,
            rateLimit = config.rateLimit,
        )
    }

    internal fun toWire(req: ModelRequest): ChatCompletionsRequest {
        val parallel = when (capabilities.parallelToolCalls) {
            Support.Supported -> true
            Support.Unsupported -> false
            Support.Unknown -> null
        }
        return ChatCompletionsRequest(
            model = config.modelName,
            messages = req.toChatMessages(),
            tools = req.tools.takeIf { it.isNotEmpty() }?.map { it.toChatTool() },
            parallelToolCalls = if (req.tools.isNotEmpty()) parallel else null,
            stream = true,
            streamOptions = ChatStreamOptions(includeUsage = true),
            temperature = params.temperature,
            maxTokens = params.maxTokens,
            maxCompletionTokens = params.maxCompletionTokens,
            topP = params.topP,
            stop = params.stop,
            presencePenalty = params.presencePenalty,
            frequencyPenalty = params.frequencyPenalty,
            reasoningEffort = params.reasoningEffort,
            enableThinking = params.enableThinking,
            responseFormat = req.outputFormat.toResponseFormat(),
        )
    }
}

fun ModelRequest.toChatMessages(): List<ChatMessage> {
    val out = mutableListOf<ChatMessage>()
    instructions?.takeIf { it.isNotBlank() }?.let {
        out += ChatMessage(role = "system", content = it)
    }
    for (item in items) {
        when (item) {
            is ModelItem.Message -> when (item.role) {
                Role.SYSTEM -> out += ChatMessage(role = "system", content = item.text)
                Role.USER -> out += ChatMessage(role = "user", content = item.text.ifEmpty { null })
                Role.ASSISTANT -> {
                    val calls = emptyList<ChatReqToolCall>()
                    out += ChatMessage(
                        role = "assistant",
                        content = item.text.ifEmpty { null },
                        toolCalls = calls.takeIf { it.isNotEmpty() },
                    )
                }
                Role.TOOL -> Unit
            }
            is ModelItem.ToolCall -> {
                val last = out.lastOrNull()
                val native = item.nativeId?.raw ?: item.ref.value
                val call = ChatReqToolCall(
                    id = native,
                    function = ChatReqFunction(item.name, item.arguments),
                )
                if (last != null && last.role == "assistant") {
                    out[out.lastIndex] = last.copy(
                        toolCalls = (last.toolCalls ?: emptyList()) + call,
                    )
                } else {
                    out += ChatMessage(role = "assistant", toolCalls = listOf(call))
                }
            }
            is ModelItem.ToolResult -> {
                val callId = items.filterIsInstance<ModelItem.ToolCall>()
                    .firstOrNull { it.ref == item.callRef }
                    ?.let { it.nativeId?.raw ?: it.ref.value }
                    ?: item.callRef.value
                out += ChatMessage(role = "tool", content = item.output, toolCallId = callId)
            }
            is ModelItem.ReasoningSummary -> Unit
            is ModelItem.ProviderItem -> item.ensureDroppable("chat-completions")
        }
    }
    return out
}

private fun ToolSchema.toChatTool() = ChatTool(
    function = ChatFunctionDef(name, description, parameters),
)

private fun OutputFormat.toResponseFormat(): JsonObject? = when (this) {
    OutputFormat.Text -> null
    OutputFormat.JsonObject -> buildJsonObject { put("type", JsonPrimitive("json_object")) }
    is OutputFormat.JsonSchema -> buildJsonObject {
        put("type", JsonPrimitive("json_schema"))
        put(
            "json_schema",
            buildJsonObject {
                put("name", JsonPrimitive(name))
                put("schema", schema)
                put("strict", JsonPrimitive(strict))
            },
        )
    }
}

fun normalizeChatCompletionsUrl(baseUrl: String): String {
    val trimmed = baseUrl.trim().trimEnd('/')
    return when {
        trimmed.endsWith("/chat/completions") -> trimmed
        trimmed.endsWith("/v1") -> "$trimmed/chat/completions"
        else -> "$trimmed/v1/chat/completions"
    }
}
