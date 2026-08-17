package org.koaks.provider.anthropic

import kotlinx.serialization.json.JsonObject
import org.koaks.framework.model.AgentError
import org.koaks.framework.model.AgentFrameworkException
import org.koaks.framework.model.ContentPart
import org.koaks.framework.model.ModelCapabilities
import org.koaks.framework.model.ModelItem
import org.koaks.framework.model.ModelRequest
import org.koaks.framework.model.ProviderId
import org.koaks.framework.model.Role
import org.koaks.framework.model.rawFor
import org.koaks.framework.model.ensureDroppable
import org.koaks.framework.provider.ChatModel
import org.koaks.framework.provider.ModelConfig
import org.koaks.framework.provider.WireDecoder
import org.koaks.framework.provider.authHeaders
import org.koaks.framework.provider.timeouts
import org.koaks.framework.transport.Framing
import org.koaks.framework.transport.HttpMethod
import org.koaks.framework.transport.ModelTransport
import org.koaks.framework.transport.WireCall
import org.koaks.framework.utils.json.JsonUtil

class AnthropicChatModel(
    config: ModelConfig,
    transport: ModelTransport,
    private val params: AnthropicParams = AnthropicParams(),
    override val capabilities: ModelCapabilities = ModelCapabilities(
        jsonObject = org.koaks.framework.model.Support.Unsupported,
        assistantPrefill = org.koaks.framework.model.Support.Supported,
    ),
) : ChatModel(config, transport) {

    override fun newDecoder(): WireDecoder = AnthropicWireDecoder()

    override fun newDecoder(request: ModelRequest): WireDecoder = AnthropicWireDecoder(request.eventDetail)

    override fun toWireCall(req: ModelRequest): WireCall {
        val body = JsonUtil.toJson(toWire(req), AnthropicChatRequest.serializer())
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

    internal fun toWire(req: ModelRequest): AnthropicChatRequest {
        val system = listOfNotNull(req.instructions)
            .plus(req.items.filterIsInstance<ModelItem.Message>().filter { it.role == Role.SYSTEM }.map { it.text })
            .joinToString("\n")
            .ifBlank { null }
        return AnthropicChatRequest(
            model = config.modelName,
            maxTokens = params.maxTokens,
            messages = toAnthropicMessages(req.items),
            system = system,
            tools = req.tools.takeIf { it.isNotEmpty() }?.map { schema ->
                AnthropicTool(schema.name, schema.description, schema.parameters)
            },
            stream = true,
            temperature = params.temperature,
            topP = params.topP,
            topK = params.topK,
            stopSequences = params.stopSequences,
            thinking = params.thinking,
        )
    }
}

data class AnthropicParams(
    val maxTokens: Int = 4096,
    val temperature: Double? = null,
    val topP: Double? = null,
    val topK: Int? = null,
    val stopSequences: List<String>? = null,
    val thinking: JsonObject? = null,
)

fun toAnthropicMessages(items: List<ModelItem>): List<AnthropicMessage> {
    val out = mutableListOf<AnthropicMessage>()
    val nonSystem = items.filterNot { it is ModelItem.Message && it.role == Role.SYSTEM }
    var i = 0
    while (i < nonSystem.size) {
        when (val item = nonSystem[i]) {
            is ModelItem.ToolResult -> {
                val blocks = mutableListOf<AnthropicContentBlock>()
                while (i < nonSystem.size && nonSystem[i] is ModelItem.ToolResult) {
                    val result = nonSystem[i] as ModelItem.ToolResult
                    val call = items.filterIsInstance<ModelItem.ToolCall>().firstOrNull { it.ref == result.callRef }
                    val toolUseId = call?.nativeId.rawFor(ProviderId.Anthropic) ?: result.callRef.value
                    blocks += AnthropicContentBlock.ToolResult(
                        toolUseId = toolUseId,
                        content = result.output,
                        isError = result.isError,
                    )
                    i++
                }
                out += AnthropicMessage(role = "user", content = blocks)
            }
            is ModelItem.Message -> if (item.role == Role.USER) {
                val blocks = mutableListOf<AnthropicContentBlock>()
                item.content.filterIsInstance<ContentPart.Text>().forEach { blocks += AnthropicContentBlock.Text(it.text) }
                if (blocks.isEmpty()) blocks += AnthropicContentBlock.Text(item.text)
                out += AnthropicMessage(role = "user", content = blocks)
                i++
            } else {
                i = consumeAssistantTurn(nonSystem, i, out)
            }
            is ModelItem.ToolCall, is ModelItem.ProviderItem, is ModelItem.ReasoningSummary -> {
                i = consumeAssistantTurn(nonSystem, i, out)
            }
        }
    }
    return out
}

private fun consumeAssistantTurn(
    items: List<ModelItem>,
    start: Int,
    out: MutableList<AnthropicMessage>,
): Int {
    val blocks = mutableListOf<AnthropicContentBlock>()
    var i = start
    while (i < items.size) {
        when (val item = items[i]) {
            is ModelItem.ProviderItem -> {
                if (item.providerId == ProviderId.Anthropic &&
                    (item.kind == "thinking" || item.kind == "redacted_thinking")
                ) {
                    val json = item.payload.utf8()
                    val block = runCatching {
                        JsonUtil.fromJson(json, AnthropicContentBlock.serializer())
                    }.getOrElse { error ->
                        throw AgentFrameworkException(
                            AgentError.PreparationError(
                                component = "anthropic",
                                message = "failed to replay ${item.kind} block",
                                cause = error,
                            ),
                        )
                    }
                    blocks += block
                } else {
                    item.ensureDroppable("anthropic")
                }
                i++
            }
            is ModelItem.Message -> {
                if (item.role != Role.ASSISTANT) break
                if (item.text.isNotEmpty()) blocks += AnthropicContentBlock.Text(item.text)
                i++
            }
            is ModelItem.ToolCall -> {
                blocks += AnthropicContentBlock.ToolUse(
                    id = item.nativeId.rawFor(ProviderId.Anthropic) ?: item.ref.value,
                    name = item.name,
                    input = JsonUtil.json.parseToJsonElement(item.arguments.ifBlank { "{}" }),
                )
                i++
            }
            is ModelItem.ReasoningSummary -> i++
            is ModelItem.ToolResult -> break
        }
    }
    if (blocks.isNotEmpty()) out += AnthropicMessage(role = "assistant", content = blocks)
    return i
}
