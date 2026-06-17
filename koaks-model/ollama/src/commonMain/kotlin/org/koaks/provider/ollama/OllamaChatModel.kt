package org.koaks.provider.ollama

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.koaks.framework.model.ChatRequest
import org.koaks.framework.model.ContentPart
import org.koaks.framework.model.Message
import org.koaks.framework.model.ModelCapabilities
import org.koaks.framework.model.Role
import org.koaks.framework.provider.ChatModel
import org.koaks.framework.provider.WireDecoder
import org.koaks.framework.transport.ModelConfig
import org.koaks.framework.transport.Transport
import org.koaks.framework.transport.WireAdapter

/**
 * Ollama provider. Implements only [toWire] / [adapter] / [newDecoder] / [capabilities];
 * fully decoupled from the agent loop. Streams NDJSON (see [OllamaWireDecoder]).
 *
 * Ollama's default models do not support native JSON mode the OpenAI way and tool
 * support varies by model, so [capabilities] leaves those to the developer to set.
 */
class OllamaChatModel(
    config: ModelConfig,
    transport: Transport,
    override val capabilities: ModelCapabilities = ModelCapabilities(parallelToolCalls = false),
) : ChatModel<OllamaChatRequest, OllamaChatResponse>(config, transport) {

    override val adapter = WireAdapter(
        requestSerializer = OllamaChatRequest.serializer(),
        responseSerializer = OllamaChatResponse.serializer(),
    )

    override fun newDecoder(): WireDecoder<OllamaChatResponse> = OllamaWireDecoder()

    override fun toWire(req: ChatRequest): OllamaChatRequest {
        // Agent (request-level) params override the model's defaults, field by field.
        val p = req.params.over(config.defaultParams)
        val options = OllamaOptions(
            temperature = p.temperature,
            topP = p.topP,
            numPredict = p.maxTokens,
            stop = p.stop,
        )
        return OllamaChatRequest(
            model = config.modelName,
            messages = req.messages.map { it.toWire() },
            tools = req.tools.takeIf { it.isNotEmpty() }?.map { schema ->
                OllamaTool(function = OllamaFunctionDef(schema.name, schema.description, schema.parameters))
            },
            stream = req.stream,
            format = if (req.jsonMode) "json" else null,
            think = p.reasoning,
            options = options.takeIf { it != OllamaOptions() },
        )
    }
}

private val argsJson = Json { ignoreUnknownKeys = true; isLenient = true }

private fun parseArgs(raw: String): JsonObject =
    if (raw.isBlank()) JsonObject(emptyMap())
    else runCatching { argsJson.parseToJsonElement(raw).jsonObject }.getOrElse { JsonObject(emptyMap()) }

private fun Message.toWire(): OllamaMessage {
    val roleStr = when (role) {
        Role.SYSTEM -> "system"
        Role.USER -> "user"
        Role.ASSISTANT -> "assistant"
        Role.TOOL -> "tool"
    }

    // Tool result message: single ToolResultPart → role=tool.
    val toolResult = parts.filterIsInstance<ContentPart.ToolResultPart>().firstOrNull()
    if (toolResult != null) {
        return OllamaMessage(role = "tool", content = toolResult.output)
    }

    val images = parts.filterIsInstance<ContentPart.Image>().mapNotNull { it.base64 }
    val toolCalls = parts.filterIsInstance<ContentPart.ToolCallPart>().map { it.call }
    return OllamaMessage(
        role = roleStr,
        content = text,
        toolCalls = toolCalls.takeIf { it.isNotEmpty() }?.map {
            OllamaReqToolCall(function = OllamaReqFunction(it.name, parseArgs(it.arguments)))
        },
        images = images.takeIf { it.isNotEmpty() },
    )
}
