package org.endow.framework.service

import com.google.gson.JsonObject
import io.github.oshai.kotlinlogging.KotlinLogging
import org.endow.framework.entity.ChatMessage
import org.endow.framework.entity.DefaultRequest
import org.endow.framework.entity.Message
import org.endow.framework.entity.ModelResponse
import org.endow.framework.memory.DefaultMemoryStorage
import org.endow.framework.memory.IMemoryStorage
import org.endow.framework.model.ChatModel
import org.endow.framework.net.HttpClient
import org.endow.framework.toolcall.ToolContainer
import org.endow.framework.toolcall.caller.ToolCaller
import org.endow.framework.utils.JsonUtil

class ChatService(
    val model: ChatModel,
    val memoryStorage: IMemoryStorage = DefaultMemoryStorage,
) {

    private val logger = KotlinLogging.logger {}
    private val MAX_TOOL_CALL_EPOCH = 30

    private var httpClient = HttpClient.create(
        baseUrl = model.baseUrl,
        apiKey = model.apiKey
    )

    suspend fun execChat(request: DefaultRequest): ModelResponse<ChatMessage> {

        with(request) {
            systemMessage = systemMessage ?: model.defaultSystemMessage
            if (model.useMcp == true) {
                systemMessage += "\n\n" + model.mcpSystemMessage
            }
            modelName = modelName ?: model.modelName
            stream = stream ?: model.stream
            parallelToolCalls = parallelToolCalls ?: model.parallelToolCalls
            responseFormat = responseFormat ?: model.responseFormat
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

        return if (request.stream == true) {
            if (request.tools?.isNotEmpty() == true) {
                logger.warn { "Streaming is not supported for tools. Falling back to non-streaming mode." }
                request.stream = false
                val initialResponse =
                    ModelResponse.fromResult(httpClient.postAsObject<ChatMessage>(request)) { ChatMessage() }
                return handleToolCall(request, initialResponse)
            }
            ModelResponse.fromStream(httpClient.postAsObjectStream<ChatMessage>(request))
        } else {
            val initialResponse =
                ModelResponse.fromResult(httpClient.postAsObject<ChatMessage>(request)) { ChatMessage() }
            handleToolCall(request, initialResponse)
        }
    }

    private suspend fun handleToolCall(
        request: DefaultRequest,
        initialResponse: ModelResponse<ChatMessage>,
    ): ModelResponse<ChatMessage> {
        val messages = request.messages
        var response = initialResponse
        var toolCallCount = 0
        val caller = ToolCaller()

        while (isToolCallResponse(response.value) && toolCallCount < MAX_TOOL_CALL_EPOCH) {
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

            toolCallCount++
            response = ModelResponse.fromResult(httpClient.postAsObject<ChatMessage>(request)) { ChatMessage() }
        }

        return response
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

    private fun isToolCallResponse(chatMessage: ChatMessage): Boolean {
        return (chatMessage.choices?.firstOrNull()?.finishReason == "tool_calls")
                && (chatMessage.choices?.firstOrNull()?.message?.toolCalls != null)
    }

}