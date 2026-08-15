package org.koaks.provider.ollama

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.koaks.framework.model.ContentPart
import org.koaks.framework.model.ModelCapabilities
import org.koaks.framework.model.ModelItem
import org.koaks.framework.model.ModelRequest
import org.koaks.framework.model.OutputFormat
import org.koaks.framework.model.Role
import org.koaks.framework.model.Support
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

class OllamaChatModel(
    config: ModelConfig,
    transport: ModelTransport,
    private val params: OllamaParams = OllamaParams(),
    override val capabilities: ModelCapabilities = ModelCapabilities(
        parallelToolCalls = Support.Unsupported,
    ),
) : ChatModel(config, transport) {

    override fun newDecoder(): WireDecoder = OllamaWireDecoder()

    override fun toWireCall(req: ModelRequest): WireCall {
        val body = JsonUtil.toJson(toWire(req), OllamaChatRequest.serializer())
        return WireCall(
            method = HttpMethod.POST,
            url = config.baseUrl,
            headers = config.authHeaders(),
            body = body,
            expect = Framing.Ndjson,
            idempotencyKey = req.idempotencyKey,
            timeouts = config.timeouts(),
            retry = config.retry,
            rateLimit = config.rateLimit,
        )
    }

    internal fun toWire(req: ModelRequest): OllamaChatRequest {
        val options = OllamaOptions(
            temperature = params.temperature,
            topP = params.topP,
            numPredict = params.maxTokens,
            stop = params.stop,
        )
        return OllamaChatRequest(
            model = config.modelName,
            messages = toOllamaMessages(req),
            tools = req.tools.takeIf { it.isNotEmpty() }?.map { schema ->
                OllamaTool(function = OllamaFunctionDef(schema.name, schema.description, schema.parameters))
            },
            stream = true,
            format = when (req.outputFormat) {
                OutputFormat.Text -> null
                OutputFormat.JsonObject, is OutputFormat.JsonSchema -> "json"
            },
            think = params.think,
            options = options.takeIf { it != OllamaOptions() },
        )
    }
}

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

internal fun toOllamaMessages(req: ModelRequest): List<OllamaMessage> {
    val out = mutableListOf<OllamaMessage>()
    req.instructions?.takeIf { it.isNotBlank() }?.let {
        out += OllamaMessage(role = "system", content = it)
    }
    for (item in req.items) {
        when (item) {
            is ModelItem.Message -> when (item.role) {
                Role.SYSTEM -> out += OllamaMessage(role = "system", content = item.text)
                Role.USER -> {
                    val images = item.content.filterIsInstance<ContentPart.Image>().mapNotNull { it.base64 }
                    out += OllamaMessage(
                        role = "user",
                        content = item.text,
                        images = images.takeIf { it.isNotEmpty() },
                    )
                }
                Role.ASSISTANT -> out += OllamaMessage(role = "assistant", content = item.text)
                Role.TOOL -> Unit
            }
            is ModelItem.ToolCall -> {
                val last = out.lastOrNull()
                val call = OllamaReqToolCall(
                    function = OllamaReqFunction(item.name, parseArgs(item.arguments)),
                )
                if (last != null && last.role == "assistant") {
                    out[out.lastIndex] = last.copy(toolCalls = (last.toolCalls ?: emptyList()) + call)
                } else {
                    out += OllamaMessage(role = "assistant", toolCalls = listOf(call))
                }
            }
            is ModelItem.ToolResult -> out += OllamaMessage(role = "tool", content = item.output)
            is ModelItem.ReasoningSummary -> Unit
            is ModelItem.ProviderItem -> item.ensureDroppable("ollama")
        }
    }
    return out
}
