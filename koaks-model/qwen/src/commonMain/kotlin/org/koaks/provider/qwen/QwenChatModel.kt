package org.koaks.provider.qwen

import org.koaks.framework.model.ChatRequest
import org.koaks.framework.model.ContentPart
import org.koaks.framework.model.Message
import org.koaks.framework.model.ModelCapabilities
import org.koaks.framework.model.Role
import org.koaks.framework.provider.ChatModel
import org.koaks.framework.provider.WireDecoder
import org.koaks.framework.transport.ModelConfig
import org.koaks.framework.transport.Transport
import org.koaks.framework.provider.WireAdapter

/**
 * Qwen provider. Implements only [toWire] / [adapter] / [newDecoder] / [capabilities];
 * it is completely decoupled from the agent loop.
 */
class QwenChatModel(
    config: ModelConfig,
    transport: Transport,
    override val capabilities: ModelCapabilities = ModelCapabilities(),
) : ChatModel<QwenChatRequest, QwenChatResponse>(config, transport) {

    override val adapter = WireAdapter(
        requestSerializer = QwenChatRequest.serializer(),
        responseSerializer = QwenChatResponse.serializer(),
    )

    override fun newDecoder(): WireDecoder<QwenChatResponse> = QwenWireDecoder()

    override fun toWire(req: ChatRequest): QwenChatRequest {
        // Agent (request-level) params override the model's defaults, field by field.
        val p = req.params.over(config.defaultParams)
        return QwenChatRequest(
            model = config.modelName,
            messages = req.messages.map { it.toWire() },
            tools = req.tools.takeIf { it.isNotEmpty() }?.map { schema ->
                QwenTool(function = QwenFunctionDef(schema.name, schema.description, schema.parameters))
            },
            parallelToolCalls = if (req.tools.isNotEmpty()) capabilities.parallelToolCalls else null,
            stream = req.stream,
            streamOptions = if (req.stream) QwenStreamOptions(includeUsage = true) else null,
            temperature = p.temperature,
            maxTokens = p.maxTokens,
            topP = p.topP,
            stop = p.stop,
            presencePenalty = p.presencePenalty,
            frequencyPenalty = p.frequencyPenalty,
            responseFormat = if (req.jsonMode) mapOf("type" to "json_object") else null,
            enableThinking = p.reasoning,
        )
    }
}

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
