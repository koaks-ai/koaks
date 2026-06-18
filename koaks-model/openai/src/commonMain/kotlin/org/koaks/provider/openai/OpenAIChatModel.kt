package org.koaks.provider.openai

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

/**
 * OpenAI Chat Completions provider. Implements only [toWire] / [adapter] / [newDecoder]
 * / [capabilities]; it is completely decoupled from the agent loop.
 *
 * Generation params are OpenAI-native and bound to the model (set in the `openai { }`
 * DSL), carried in [params] — there is no cross-provider param abstraction.
 */
class OpenAIChatModel(
    config: ModelConfig,
    transport: Transport,
    private val params: OpenAIParams = OpenAIParams(),
    override val capabilities: ModelCapabilities = ModelCapabilities(),
) : ChatModel<OpenAIChatRequest, OpenAIChatResponse>(config, transport) {

    override val adapter = WireAdapter(
        requestSerializer = OpenAIChatRequest.serializer(),
        responseSerializer = OpenAIChatResponse.serializer(),
    )

    override fun newDecoder(): WireDecoder<OpenAIChatResponse> = OpenAIWireDecoder()

    override fun toWire(req: ChatRequest): OpenAIChatRequest {
        return OpenAIChatRequest(
            model = config.modelName,
            messages = req.messages.map { it.toWire() },
            tools = req.tools.takeIf { it.isNotEmpty() }?.map { schema ->
                OpenAITool(function = OpenAIFunctionDef(schema.name, schema.description, schema.parameters))
            },
            parallelToolCalls = if (req.tools.isNotEmpty()) capabilities.parallelToolCalls else null,
            stream = req.stream,
            streamOptions = if (req.stream) OpenAIStreamOptions(includeUsage = true) else null,
            temperature = params.temperature,
            maxCompletionTokens = params.maxCompletionTokens,
            topP = params.topP,
            stop = params.stop,
            presencePenalty = params.presencePenalty,
            frequencyPenalty = params.frequencyPenalty,
            reasoningEffort = params.reasoningEffort,
            responseFormat = if (req.jsonMode) mapOf("type" to "json_object") else null,
        )
    }
}

/**
 * OpenAI-native generation params. Set via the `openai { }` DSL and consumed directly
 * in [OpenAIChatModel.toWire]. `maxCompletionTokens` maps to OpenAI's
 * `max_completion_tokens`; `reasoningEffort` to `reasoning_effort` (`"low"|"medium"|"high"`).
 */
data class OpenAIParams(
    val temperature: Double? = null,
    val maxCompletionTokens: Int? = null,
    val topP: Double? = null,
    val stop: List<String>? = null,
    val presencePenalty: Double? = null,
    val frequencyPenalty: Double? = null,
    val reasoningEffort: String? = null,
)

private fun Message.toWire(): OpenAIMessage {
    val roleStr = when (role) {
        Role.SYSTEM -> "system"
        Role.USER -> "user"
        Role.ASSISTANT -> "assistant"
        Role.TOOL -> "tool"
    }

    // Tool result message: single ToolResultPart → role=tool with tool_call_id.
    val toolResult = parts.filterIsInstance<ContentPart.ToolResultPart>().firstOrNull()
    if (toolResult != null) {
        return OpenAIMessage(role = "tool", content = toolResult.output, toolCallId = toolResult.callId)
    }

    val toolCalls = parts.filterIsInstance<ContentPart.ToolCallPart>().map { it.call }
    return OpenAIMessage(
        role = roleStr,
        content = text.ifEmpty { null },
        toolCalls = toolCalls.takeIf { it.isNotEmpty() }?.map {
            OpenAIReqToolCall(id = it.id, function = OpenAIReqFunction(it.name, it.arguments))
        },
    )
}
