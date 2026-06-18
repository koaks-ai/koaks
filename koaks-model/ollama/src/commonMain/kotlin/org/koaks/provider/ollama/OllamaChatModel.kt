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
import org.koaks.framework.provider.ModelConfig
import org.koaks.framework.transport.Transport
import org.koaks.framework.provider.WireAdapter

/**
 * Ollama provider. Implements only [toWire] / [adapter] / [newDecoder] / [capabilities];
 * fully decoupled from the agent loop. Streams NDJSON (see [OllamaWireDecoder]).
 *
 * Ollama's default models do not support native JSON mode the OpenAI way and tool
 * support varies by model, so [capabilities] leaves those to the developer to set.
 *
 * Generation params are Ollama-native and bound to the model (set in the `ollama { }`
 * DSL), carried in [params].
 */
class OllamaChatModel(
    config: ModelConfig,
    transport: Transport,
    private val params: OllamaParams = OllamaParams(),
    override val capabilities: ModelCapabilities = ModelCapabilities(parallelToolCalls = false),
) : ChatModel<OllamaChatRequest, OllamaChatResponse>(config, transport) {

    override val adapter = WireAdapter(
        requestSerializer = OllamaChatRequest.serializer(),
        responseSerializer = OllamaChatResponse.serializer(),
    )

    override fun newDecoder(): WireDecoder<OllamaChatResponse> = OllamaWireDecoder()

    override fun toWire(req: ChatRequest): OllamaChatRequest {
        val options = OllamaOptions(
            temperature = params.temperature,
            topP = params.topP,
            numPredict = params.maxTokens,
            stop = params.stop,
        )
        return OllamaChatRequest(
            model = config.modelName,
            messages = req.messages.map { it.toWire() },
            tools = req.tools.takeIf { it.isNotEmpty() }?.map { schema ->
                OllamaTool(function = OllamaFunctionDef(schema.name, schema.description, schema.parameters))
            },
            stream = req.stream,
            format = if (req.jsonMode) "json" else null,
            think = params.think,
            options = options.takeIf { it != OllamaOptions() },
        )
    }
}

/**
 * Ollama-native generation params. Set via the `ollama { }` DSL and consumed directly
 * in [OllamaChatModel.toWire]. `maxTokens` maps to Ollama's `num_predict`; `think`
 * maps to Ollama's `think` and is surfaced as `ReasoningDelta` events.
 */
data class OllamaParams(
    val temperature: Double? = null,
    val topP: Double? = null,
    val maxTokens: Int? = null,
    val stop: List<String>? = null,
    val think: Boolean? = null,
)

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
