package org.koaks.provider.qwen

import org.koaks.framework.model.ChatRequest
import org.koaks.framework.model.ContentPart
import org.koaks.framework.model.Message
import org.koaks.framework.model.ModelCapabilities
import org.koaks.framework.model.Role
import org.koaks.framework.provider.ChatModel
import org.koaks.framework.provider.WireDecoder
import org.koaks.framework.provider.ModelConfig
import org.koaks.framework.transport.Transport
import org.koaks.framework.provider.WireAdapter

/**
 * Qwen provider. Implements only [toWire] / [adapter] / [newDecoder] / [capabilities];
 * it is completely decoupled from the agent loop.
 *
 * Generation params are Qwen-native and bound to the model (set in the `qwen { }`
 * DSL), carried in [params] — there is no cross-provider param abstraction.
 */
class QwenChatModel(
    config: ModelConfig,
    transport: Transport,
    private val params: QwenParams = QwenParams(),
    override val capabilities: ModelCapabilities = ModelCapabilities(),
) : ChatModel<QwenChatRequest, QwenChatResponse>(config, transport) {

    override val adapter = WireAdapter(
        requestSerializer = QwenChatRequest.serializer(),
        responseSerializer = QwenChatResponse.serializer(),
    )

    override fun newDecoder(): WireDecoder<QwenChatResponse> = QwenWireDecoder()

    override fun toWire(req: ChatRequest): QwenChatRequest {
        return QwenChatRequest(
            model = config.modelName,
            messages = req.messages.map { it.toWire() },
            tools = req.tools.takeIf { it.isNotEmpty() }?.map { schema ->
                QwenTool(function = QwenFunctionDef(schema.name, schema.description, schema.parameters))
            },
            parallelToolCalls = if (req.tools.isNotEmpty()) capabilities.parallelToolCalls else null,
            stream = req.stream,
            streamOptions = if (req.stream) QwenStreamOptions(includeUsage = true) else null,
            temperature = params.temperature,
            maxTokens = params.maxTokens,
            topP = params.topP,
            stop = params.stop,
            presencePenalty = params.presencePenalty,
            frequencyPenalty = params.frequencyPenalty,
            responseFormat = if (req.jsonMode) mapOf("type" to "json_object") else null,
            enableThinking = params.enableThinking,
        )
    }
}

/**
 * Qwen-native generation params. Set via the `qwen { }` DSL and consumed directly in
 * [QwenChatModel.toWire]. `enableThinking` maps to Qwen's `enable_thinking`.
 */
data class QwenParams(
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val topP: Double? = null,
    val stop: List<String>? = null,
    val presencePenalty: Double? = null,
    val frequencyPenalty: Double? = null,
    val enableThinking: Boolean? = null,
)

private fun Message.toWire(): QwenMessage {
    val roleStr = when (role) {
        Role.SYSTEM -> "system"
        Role.USER -> "user"
        Role.ASSISTANT -> "assistant"
        Role.TOOL -> "tool"
    }

    // Tool result message: single ToolResultPart → role=tool with tool_call_id.
    val toolResult = parts.filterIsInstance<ContentPart.ToolResultPart>().firstOrNull()
    if (toolResult != null) {
        return QwenMessage(role = "tool", content = toolResult.output, toolCallId = toolResult.callId)
    }

    val toolCalls = parts.filterIsInstance<ContentPart.ToolCallPart>().map { it.call }
    return QwenMessage(
        role = roleStr,
        content = text.ifEmpty { null },
        toolCalls = toolCalls.takeIf { it.isNotEmpty() }?.map {
            QwenReqToolCall(id = it.id, function = QwenReqFunction(it.name, it.arguments))
        },
    )
}
