package org.koaks.framework.service

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.koaks.framework.entity.chat.ChatResponse
import org.koaks.framework.entity.chat.ChatRequest
import org.koaks.framework.entity.inner.InnerChatRequest
import org.koaks.framework.entity.Message
import org.koaks.framework.entity.ModelResponse
import org.koaks.framework.memory.DefaultMemoryStorage
import org.koaks.framework.memory.IMemoryStorage
import org.koaks.framework.model.AbstractChatModel
import org.koaks.framework.net.HttpClient
import org.koaks.framework.net.HttpClientConfig
import org.koaks.framework.toolcall.caller.ToolCaller


class ChatService<TRequest, TResponse>(
    val model: AbstractChatModel<TRequest, TResponse>,
    val memoryStorage: IMemoryStorage = DefaultMemoryStorage,
) {

    companion object {
        private val logger = KotlinLogging.logger {}
        private const val MAX_TOOL_CALL_EPOCH = 30
    }

    private var httpClient = HttpClient(
        HttpClientConfig(
            baseUrl = model.baseUrl, apiKey = model.apiKey
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
    suspend fun execChat(chatRequest: ChatRequest, memoryId: String? = null): ModelResponse<ChatResponse> {
        mergeToolList(chatRequest)
        val messageList = mergeMessageList(chatRequest.message, memoryId)
        val request = mapToInnerRequest(chatRequest, messageList)

        return if (request.stream == true && request.tools.isNullOrEmpty()) {
            val responseContent = StringBuilder()
            val streamFlow =
                httpClient.postAsObjectStream(request, model.responseDeserializer)
                    .map { model.toChatResponse(it) }
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
                })
        } else {
            if (request.stream == true) {
                request.stream = false
                logger.warn { "Streaming is not supported for tools. Falling back to non-streaming mode." }
            }
            val initialResp = ModelResponse.fromResult(
                httpClient.postAsObject(request, model.responseDeserializer)
                    .map { model.toChatResponse(it) }
            ) { ChatResponse() }
            // mapper ChatMessage.id to Message.id
            initialResp.value.apply {
                choices?.forEach { it.message?.id = this.id }
            }
            saveMessage(initialResp.value.choices?.firstOrNull()?.message, memoryId, request.messages)
            handleToolCall(request, initialResp)
        }
    }

    private suspend fun handleToolCall(
        request: InnerChatRequest,
        initialResponse: ModelResponse<ChatResponse>,
    ): ModelResponse<ChatResponse> {
        val messages = request.messages
        var response = initialResponse
        var toolCallCount = 0
        val caller = ToolCaller

        while (response.value.shouldToolCall && toolCallCount < MAX_TOOL_CALL_EPOCH) {
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
            response = ModelResponse.fromResult(
                httpClient.postAsObject(request, model.responseDeserializer)
                    .map { model.toChatResponse(it) }
            ) { ChatResponse() }

            response.value.choices?.getOrNull(0)?.message.let {
                saveMessage(it, request.messageId, messages)
            }
        }

        return response
    }

    private suspend fun executeToolCall(
        tool: ChatResponse.ToolCall, caller: ToolCaller, request: InnerChatRequest, messages: MutableList<Message>
    ) {
        val toolName = tool.function?.name.orEmpty()
        val argsJson = tool.function?.arguments ?: ""
        val result = caller.call(toolName, argsJson)

        logger.info { "tool_call: id=${tool.id}, name=$toolName, args=$argsJson" }
        saveMessage(Message.tool(result, tool.id), request.messageId, messages)
    }

    private val mutex = Mutex()

    private suspend fun saveMessage(message: Message?, messageId: String?, messages: MutableList<Message>) {
        mutex.withLock {
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
    }

    /**
     * Merge model tool list and request tool list.
     */
    private fun mergeToolList(chatRequest: ChatRequest) {
        chatRequest.params.tools = (chatRequest.params.tools.orEmpty() + model.tools.orEmpty())
            .distinct()
            .takeIf { it.isNotEmpty() }
            ?.toMutableList()
    }

    private suspend fun mergeMessageList(message: String, messageId: String?): MutableList<Message> {
        return (messageId?.let {
            // need to call toMutableList() to create a copy, avoiding modification of the original reference
            memoryStorage.getMessageList(messageId).toMutableList()
        } ?: mutableListOf()).apply {
            saveMessage(Message.user(message), messageId, this)
        }
    }

    private fun mapToInnerRequest(
        chatRequest: ChatRequest,
        messageList: MutableList<Message>
    ): InnerChatRequest {
        val params = chatRequest.params
        return InnerChatRequest(
            modelName = chatRequest.modelName ?: model.modelName,
            messages = messageList
        ).apply {
            systemMessage = params.systemMessage ?: model.systemMessage
            tools = params.tools ?: model.tools
            parallelToolCalls = params.parallelToolCalls ?: model.parallelToolCalls
            stop = params.stop ?: model.stop
            responseFormat = params.responseFormat ?: model.responseFormat
            maxTokens = params.maxTokens ?: model.maxTokens
            temperature = params.temperature ?: model.temperature
            topP = params.topP ?: model.topP
            n = params.n ?: model.n
            stream = params.stream ?: model.stream
            useMcp = params.useMcp
            presencePenalty = params.presencePenalty ?: model.presencePenalty
            frequencyPenalty = params.frequencyPenalty ?: model.frequencyPenalty
            logitBias = params.logitBias ?: model.logitBias
        }
    }

}