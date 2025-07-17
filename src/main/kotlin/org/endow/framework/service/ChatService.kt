package org.endow.framework.service

import com.google.gson.JsonObject
import io.github.oshai.kotlinlogging.KotlinLogging
import org.endow.framework.entity.ChatResponse
import org.endow.framework.entity.DefaultRequest
import org.endow.framework.entity.Message
import org.endow.framework.entity.ModelResponse
import org.endow.framework.model.ChatModel
import org.endow.framework.net.HttpClient
import org.endow.framework.toolcall.ToolContainer
import org.endow.framework.toolcall.caller.ToolCaller
import org.endow.framework.utils.JsonUtil

class ChatService(val model: ChatModel) {

    private val logger = KotlinLogging.logger {}

    private var httpClient = HttpClient.create(
        baseUrl = model.baseUrl,
        apiKey = model.apiKey
    )

    suspend fun execChat(request: DefaultRequest): ModelResponse<ChatResponse> {

        with(request) {
            systemMessage = systemMessage ?: model.defaultSystemMessage
            if (model.useMcp == true) {
                systemMessage += "\n\n" + model.mcpSystemMessage
            }
            modelName = modelName ?: model.modelName
            stream = stream ?: model.stream
            tools = tools ?: model.tools
            maxTokens = maxTokens ?: model.maxTokens
            temperature = temperature ?: model.temperature
            topP = topP ?: model.topP
            n = n ?: model.n
            stop = stop ?: model.stop
            presencePenalty = presencePenalty ?: model.presencePenalty
            frequencyPenalty = frequencyPenalty ?: model.frequencyPenalty
            logitBias = logitBias ?: model.logitBias
        }
        val messages = request.messages
        return if (request.stream == true) {
            if (request.tools?.isNotEmpty() == true) {
                logger.warn { "Streaming is not supported for tools. Falling back to non-streaming mode." }
                request.tools = null
            }
            ModelResponse.fromStream(httpClient.postAsObjectStream<ChatResponse>(request))
        } else {
            var response =
                ModelResponse.fromResult(httpClient.postAsObject<ChatResponse>(request)) { ChatResponse() }
            val caller = ToolCaller()
            while (isToolCallResponse(response.value)) {
                val message = response.value.choices?.firstOrNull()?.message
                message?.let { messages.add(it) }

                message?.toolCalls.orEmpty().forEach { tool ->
                    val toolName = tool.function?.name.orEmpty()
                    val argsJson = tool.function?.arguments
                    val args = parseToolArguments(toolName, argsJson)
                    val result = caller.call(toolName, args.toTypedArray())
                    logger.info { "tool call: $toolName, args: $args" }
                    messages.add(Message.tool(result, tool.id))
                }

                response = ModelResponse.fromResult(httpClient.postAsObject<ChatResponse>(request)) { ChatResponse() }
            }
            response
        }
    }

    private fun parseToolArguments(toolName: String, rawJson: String?): List<Any> {
        if (rawJson.isNullOrBlank()) return emptyList()

        return try {
            val jsonObject = JsonUtil.fromJson<JsonObject>(rawJson)
            val tool = ToolContainer.getTool(toolName)
            tool?.function?.parameters?.properties?.mapNotNull { (key, _) ->
                jsonObject.get(key)?.toString()
            } ?: emptyList()
        } catch (e: Exception) {
            logger.error(e) { "Failed to parse arguments for $toolName" }
            emptyList()
        }
    }

    private fun isToolCallResponse(chatResponse: ChatResponse): Boolean {
        return (chatResponse.choices?.firstOrNull()?.finishReason == "tool_calls")
                && (chatResponse.choices?.firstOrNull()?.message?.toolCalls != null)
    }

}