package org.koaks.provider.anthropic

import kotlinx.serialization.json.JsonObject
import org.koaks.framework.model.ChatRequest
import org.koaks.framework.model.ContentPart
import org.koaks.framework.model.Message
import org.koaks.framework.model.ModelCapabilities
import org.koaks.framework.model.Role
import org.koaks.framework.provider.ChatModel
import org.koaks.framework.provider.ModelConfig
import org.koaks.framework.provider.WireAdapter
import org.koaks.framework.provider.WireDecoder
import org.koaks.framework.transport.Transport
import org.koaks.framework.utils.json.JsonUtil

/**
 * Anthropic Messages API provider (POST /v1/messages). Implements only [toWire] /
 * [adapter] / [newDecoder] / [capabilities]; it is fully decoupled from the agent loop.
 *
 * Generation params are Anthropic-native and bound to the model (set in the
 * `anthropic { }` DSL), carried in [params] — there is no cross-provider param abstraction.
 */
class AnthropicChatModel(
    config: ModelConfig,
    transport: Transport,
    private val params: AnthropicParams = AnthropicParams(),
    override val capabilities: ModelCapabilities = ModelCapabilities(),
) : ChatModel<AnthropicChatRequest, AnthropicChatResponse>(config, transport) {

    override val adapter = WireAdapter(
        requestSerializer = AnthropicChatRequest.serializer(),
        responseSerializer = AnthropicChatResponse.serializer(),
    )

    override fun newDecoder(): WireDecoder<AnthropicChatResponse> = AnthropicWireDecoder()

    override fun toWire(req: ChatRequest): AnthropicChatRequest {
        // System messages are hoisted to the top-level `system` param (not a role).
        val system = req.messages
            .filter { it.role == Role.SYSTEM }
            .joinToString("\n") { it.text }
            .ifBlank { null }

        return AnthropicChatRequest(
            model = config.modelName,
            maxTokens = params.maxTokens,
            messages = toAnthropicMessages(req.messages),
            system = system,
            tools = req.tools.takeIf { it.isNotEmpty() }?.map { schema ->
                AnthropicTool(schema.name, schema.description, schema.parameters)
            },
            stream = req.stream,
            temperature = params.temperature,
            topP = params.topP,
            topK = params.topK,
            stopSequences = params.stopSequences,
            thinking = params.thinking,
        )
    }
}

/**
 * Anthropic-native generation params. Set via the `anthropic { }` DSL and consumed
 * directly in [AnthropicChatModel.toWire]. [maxTokens] maps to the required
 * `max_tokens`; [thinking] is the optional extended-thinking opt-in object.
 *
 * Note: `temperature` / `topP` / `topK` are rejected (HTTP 400) by Opus 4.7+ /
 * Fable thinking-only models — leave them unset there; they are safe on Sonnet/Haiku.
 */
data class AnthropicParams(
    val maxTokens: Int = 4096,
    val temperature: Double? = null,
    val topP: Double? = null,
    val topK: Int? = null,
    val stopSequences: List<String>? = null,
    val thinking: JsonObject? = null,
)

/**
 * Maps the unified messages to Anthropic's user/assistant turns. System messages are
 * dropped here (hoisted separately). Consecutive [Role.TOOL] messages are coalesced
 * into a single following user turn carrying all `tool_result` blocks — the shape
 * Anthropic expects for a multi-tool turn.
 */
private fun toAnthropicMessages(messages: List<Message>): List<AnthropicMessage> {
    val nonSystem = messages.filter { it.role != Role.SYSTEM }
    val out = mutableListOf<AnthropicMessage>()
    var i = 0
    while (i < nonSystem.size) {
        val msg = nonSystem[i]
        when (msg.role) {
            Role.TOOL -> {
                val blocks = mutableListOf<AnthropicContentBlock>()
                while (i < nonSystem.size && nonSystem[i].role == Role.TOOL) {
                    nonSystem[i].parts.filterIsInstance<ContentPart.ToolResultPart>().forEach { tr ->
                        blocks += AnthropicContentBlock.ToolResult(
                            toolUseId = tr.callId,
                            content = tr.output,
                            isError = tr.isError,
                        )
                    }
                    i++
                }
                out += AnthropicMessage(role = "user", content = blocks)
            }

            Role.ASSISTANT -> {
                val blocks = mutableListOf<AnthropicContentBlock>()
                if (msg.text.isNotEmpty()) blocks += AnthropicContentBlock.Text(msg.text)
                msg.parts.filterIsInstance<ContentPart.ToolCallPart>().forEach { tcp ->
                    blocks += AnthropicContentBlock.ToolUse(
                        id = tcp.call.id,
                        name = tcp.call.name,
                        input = JsonUtil.json.parseToJsonElement(tcp.call.arguments.ifBlank { "{}" }),
                    )
                }
                out += AnthropicMessage(role = "assistant", content = blocks)
                i++
            }

            else -> { // Role.USER (SYSTEM already filtered out)
                out += AnthropicMessage(role = "user", content = listOf(AnthropicContentBlock.Text(msg.text)))
                i++
            }
        }
    }
    return out
}
