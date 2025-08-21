package org.koaks.framework.service

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonObject
import org.koaks.framework.entity.chat.ChatMessage
import org.koaks.framework.entity.chat.ChatRequest
import org.koaks.framework.entity.inner.InnerChatRequest
import org.koaks.framework.entity.Message
import org.koaks.framework.entity.ModelResponse
import org.koaks.framework.memory.DefaultMemoryStorage
import org.koaks.framework.memory.IMemoryStorage
import org.koaks.framework.model.ChatModel
import org.koaks.framework.net.HttpClient
import org.koaks.framework.net.HttpClientConfig
import org.koaks.framework.net.postAsObject
import org.koaks.framework.net.postAsObjectStream
import org.koaks.framework.toolcall.ToolContainer
import org.koaks.framework.toolcall.caller.ToolCaller
import org.koaks.framework.utils.JsonUtil


class ChatService(
    val model: ChatModel,
    val memoryStorage: IMemoryStorage = DefaultMemoryStorage,
) {

    companion object {
        private val logger = KotlinLogging.logger {}
        private const val MAX_TOOL_CALL_EPOCH = 30
    }

    private var httpClient = HttpClient(
        HttpClientConfig(
            baseUrl = model.baseUrl,
            apiKey = model.apiKey
        )
    )

    /**
     * This method is used to execute chat requests. It converts the user's request into an internal request
     * and call httpClient to send the request, and handles operations such as tool_call.
     * @param chatRequest chat request parameters
     * @param memoryId This is the unique identifier for whether continuous conversation is enabled.
     * The `InnerChatRequest` has a `memoryId` property, but the developer-facing `ChatRequest` does not.
     * This is intentional; the external system must explicitly pass the `memoryId`.
     * This is to avoid errors that could result from ignoring the initialization of this field if it were placed inside the `ChatRequest`.
     *
     * @return `ModelResponse` object containing the response from the chat service.
     */
    suspend fun execChat(chatRequest: ChatRequest, memoryId: String? = null): ModelResponse<ChatMessage> {
        val request = chatRequest2innerRequest(chatRequest, memoryId)

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
                logger.error { "Streaming is not supported for tools. Falling back to non-streaming mode." }
                throw UnsupportedOperationException("Streaming is not supported for tools.")
            }
            val responseContent = StringBuilder()

            val streamFlow = httpClient.postAsObjectStream<ChatMessage>(request)
                .onEach { data ->
                    val chunk = data.choices?.getOrNull(0)?.delta?.content
                    if (!chunk.isNullOrEmpty()) {
                        responseContent.append(chunk)
                    }
                }

            ModelResponse.fromStream(
                streamFlow.onCompletion {
                    val assistantMessage = Message.assistant(responseContent.toString())
                    saveMessage(assistantMessage, memoryId, request.messages)
                }
            )
        } else {
            val initialResponse =
                ModelResponse.fromResult(httpClient.postAsObject<ChatMessage>(request)) { ChatMessage() }
            // mapper ChatMessage.id to Message.id
            initialResponse.value.apply {
                choices?.forEach { it.message?.id = this.id }
            }
            saveMessage(initialResponse.value.choices?.firstOrNull()?.message, memoryId, request.messages)
            handleToolCall(request, initialResponse)
        }
    }

    private suspend fun handleToolCall(
        request: InnerChatRequest,
        initialResponse: ModelResponse<ChatMessage>,
    ): ModelResponse<ChatMessage> {
        val messages = request.messages
        var response = initialResponse
        var toolCallCount = 0
        val caller = ToolCaller()

        while (isToolCallResponse(response.value) && toolCallCount < MAX_TOOL_CALL_EPOCH) {
            val responseMessage = response.value.choices?.firstOrNull()?.message
            val toolCalls = responseMessage?.toolCalls.orEmpty()
            val semaphore = Semaphore(MAX_TOOL_CALL_EPOCH)

            if (toolCalls.size <= 1) {
                executeToolCall(toolCalls[0], caller, request, messages)
            } else {
                coroutineScope {
                    toolCalls.map { tool ->
                        async(Dispatchers.Default) {
                            semaphore.withPermit {
                                executeToolCall(tool, caller, request, messages)
                            }
                        }
                    }.awaitAll()
                }
            }

            toolCallCount++
            response = ModelResponse.fromResult(httpClient.postAsObject<ChatMessage>(request)) { ChatMessage() }
            response.value.choices?.getOrNull(0)?.message.let {
                saveMessage(it, request.messageId, messages)
            }
        }

        return response
    }


    private fun executeToolCall(
        tool: ChatMessage.ToolCall,
        caller: ToolCaller,
        request: InnerChatRequest,
        messages: MutableList<Message>
    ) {
        val toolName = tool.function?.name.orEmpty()
        val argsJson = tool.function?.arguments
        val args = parseToolArguments(toolName, argsJson)
        val result = caller.call(toolName, args.toTypedArray())

        logger.info { "tool_call: id=${tool.id}, name=$toolName, args=$argsJson" }
        saveMessage(Message.tool(result, tool.id), request.messageId, messages)
    }

    // todo: need refactoring
    private fun parseToolArguments(toolName: String, rawJson: String?): List<Any> {
        if (rawJson.isNullOrBlank()) return emptyList()

        return try {
            val jsonObject = JsonUtil.fromJson<JsonObject>(rawJson)
            val tool = ToolContainer.getTool(toolName)
            tool?.function?.parameters?.properties?.mapNotNull { (key, _) ->
                jsonObject[key]?.toString()
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

    //    @Synchronized
    private fun saveMessage(message: Message?, messageId: String?, messages: MutableList<Message>) {
        if (message == null) return
        try {
            messages.add(message)
            messageId?.let {
                memoryStorage.addMessage(message, messageId)
            }
        } catch (e: Exception) {
            messages.removeLastOrNull()
            throw e
        }
    }

    private fun chatRequest2innerRequest(chatRequest: ChatRequest, messageId: String?): InnerChatRequest {
        val messages = messageId?.let {
            // need to call toMutableList() to create a copy, avoiding modification of the original reference
            memoryStorage.getMessageList(messageId).toMutableList()
        } ?: mutableListOf()

        saveMessage(Message.user(chatRequest.message), messageId, messages)

        return InnerChatRequest(
            modelName = chatRequest.modelName,
            messages = messages
        ).apply {
            systemMessage = chatRequest.params.systemMessage
            tools = chatRequest.params.tools
            parallelToolCalls = chatRequest.params.parallelToolCalls
            stop = chatRequest.params.stop
            responseFormat = chatRequest.params.responseFormat
            maxTokens = chatRequest.params.maxTokens
            temperature = chatRequest.params.temperature
            topP = chatRequest.params.topP
            n = chatRequest.params.n
            stream = chatRequest.params.stream
            useMcp = chatRequest.params.useMcp
            presencePenalty = chatRequest.params.presencePenalty
            frequencyPenalty = chatRequest.params.frequencyPenalty
            logitBias = chatRequest.params.logitBias
        }
    }

}