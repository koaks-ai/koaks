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
import org.koaks.framework.context.KoaksContext
import org.koaks.framework.entity.chat.ChatResponse
import org.koaks.framework.entity.chat.ChatRequest
import org.koaks.framework.entity.inner.FullChatRequest
import org.koaks.framework.entity.Message
import org.koaks.framework.entity.ModelResponse
import org.koaks.framework.memory.DefaultMemoryStorage
import org.koaks.framework.memory.IMemoryStorage
import org.koaks.framework.memory.NoneMemoryStorage
import org.koaks.framework.model.AbstractChatModel
import org.koaks.framework.net.KtorHttpClient
import org.koaks.framework.net.HttpClientConfig
import org.koaks.framework.toolcall.ToolManager
import org.koaks.framework.toolcall.caller.ToolCaller


class ChatService<TRequest, TResponse>(
    val model: AbstractChatModel<TRequest, TResponse>,
    val memoryStorage: IMemoryStorage = DefaultMemoryStorage,
    val clientId: String,
) {

    companion object {
        private val logger = KotlinLogging.logger {}

        /** The maximum number of times the tool call is executed. **/
        private const val MAX_TOOL_CALL_EPOCH = 30
    }

    private val httpClient = KtorHttpClient(
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
    suspend fun execChat(chatRequest: ChatRequest, memoryId: String? = null): ModelResponse<ChatResponse> {
        // distinguish between manually managed message records and automatically managed message records
        val messages = when {
            // if message memory are provided, use them first.
            chatRequest.messageList != null -> chatRequest.messageList
            chatRequest.message != null -> mergeMessageList(chatRequest.message, memoryId)
            // for the case, it should never come here
            else -> mutableListOf()
        }
        val fullReq = mergeParamsAndMapToFullRequest(chatRequest, messages.toMutableList())

        return if (fullReq.stream == true && fullReq.tools.isNullOrEmpty()) {
            handleStreaming(fullReq, memoryId)
        } else {
            handleNonStreaming(fullReq, memoryId)
        }
    }

    private fun handleStreaming(request: FullChatRequest, memoryId: String?): ModelResponse<ChatResponse> {
        val aggregator = StreamingAggregator()
        val stream = httpClient.postAsObjectStream(
            model.toChatRequest(request),
            model.typeAdapter
        ).map { model.toChatResponse(it) }
            .onEach { aggregator.accept(it) }

        return ModelResponse.fromStream(
            stream.onEach { resp ->
                if (resp.error != null) {
                    logger.error { "response error: ${resp.error}" }
                }
            }.onCompletion {
                saveMessage(
                    Message.assistantText(aggregator.result()),
                    memoryId,
                    request.messages
                )
            }
        )
    }

    private suspend fun handleNonStreaming(request: FullChatRequest, memoryId: String?): ModelResponse<ChatResponse> {
        if (request.stream == true) {
            request.stream = false
            logger.warn { "Streaming is not supported for tools. Falling back to non-streaming mode." }
        }

        val response = ModelResponse.fromResult(
            httpClient.postAsObject(
                model.toChatRequest(request), model.typeAdapter
            ).map { model.toChatResponse(it) }
        ) { ChatResponse() }

        if (response.value().error != null) {
            logger.error { "response error: ${response.value().error}" }
            return response
        }

        // mapper ChatMessage.id to Message.id
        response.value().choices?.forEach { it.message?.id = response.value().id }
        saveMessage(
            response.value().choices?.firstOrNull()?.message,
            memoryId,
            request.messages
        )

        return handleToolCall(request, response)
    }

    /**
     * Handles tool call response.
     */
    private suspend fun handleToolCall(
        request: FullChatRequest,
        initialResponse: ModelResponse<ChatResponse>,
    ): ModelResponse<ChatResponse> {
        val messages = request.messages
        var response = initialResponse
        var toolCallCount = 0
        val caller = ToolCaller

        while (response.value().shouldToolCall && toolCallCount < MAX_TOOL_CALL_EPOCH) {
            val responseMessage = response.value().choices?.firstOrNull()?.message
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
                httpClient.postAsObject(
                    model.toChatRequest(request), model.typeAdapter
                ).map { model.toChatResponse(it) }
            ) { ChatResponse() }

            response.value().choices?.getOrNull(0)?.message.let {
                saveMessage(it, request.messageId, messages)
            }
        }

        return response
    }

    /**
     * Executes tool call.
     */
    private suspend fun executeToolCall(
        tool: ChatResponse.ToolCall, caller: ToolCaller, request: FullChatRequest, messages: MutableList<Message>
    ) {
        val toolName = tool.function?.name.orEmpty()
        val argsJson = tool.function?.arguments ?: ""
        val result = caller.call(toolName, argsJson)

        logger.info { "tool_call: id=${tool.id}, name=$toolName, args=$argsJson" }
//        if (currentToolContainer[toolName]!!.returnDirectly) {
//            TODO("return directly is not supported yet")
//        }
        saveMessage(Message.tool(result, tool.id), request.messageId, messages)
    }

    private val mutex = Mutex()

    /**
     * Save message to memory storage. the function is thread-safe
     */
    private suspend fun saveMessage(message: Message?, messageId: String?, messages: MutableList<Message>) {
        if (memoryStorage::class == NoneMemoryStorage::class)
            return
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

    private suspend fun mergeMessageList(message: Message, messageId: String?): MutableList<Message> {
        return (messageId?.let {
            // need to call toMutableList() to create a copy, avoiding modification of the original reference
            memoryStorage.getMessageList(messageId).toMutableList()
        } ?: mutableListOf()).apply {
            saveMessage(message, messageId, this)
        }
    }

    private fun mergeParamsAndMapToFullRequest(
        chatRequest: ChatRequest,
        messageList: MutableList<Message>
    ): FullChatRequest {
        val chatRequestParams = chatRequest.params
        // distinguish between manually managed message records and automatically managed message records
        val finalMessages = if (chatRequest.messageList != null) {
            chatRequest.messageList.toMutableList()
        } else {
            messageList
        }

        return FullChatRequest(
            modelName = chatRequest.modelName ?: model.modelName,
            messages = finalMessages
        ).apply {
            systemMessage = chatRequestParams.systemMessage ?: model.defaultParams.systemMessage
            tools = KoaksContext.getAvailableTools(clientId)?.mapNotNull { ToolManager.getTool(it) }?.toMutableList()
            parallelToolCalls = chatRequestParams.parallelToolCalls ?: model.defaultParams.parallelToolCalls
            stop = chatRequestParams.stop ?: model.defaultParams.stop
            responseFormat = chatRequestParams.responseFormat ?: model.defaultParams.responseFormat
            maxTokens = chatRequestParams.maxTokens ?: model.defaultParams.maxTokens
            temperature = chatRequestParams.temperature ?: model.defaultParams.temperature
            topP = chatRequestParams.topP ?: model.defaultParams.topP
            n = chatRequestParams.n ?: model.defaultParams.n
            stream = chatRequestParams.stream ?: model.defaultParams.stream
            useMcp = chatRequestParams.useMcp
            presencePenalty = chatRequestParams.presencePenalty ?: model.defaultParams.presencePenalty
            frequencyPenalty = chatRequestParams.frequencyPenalty ?: model.defaultParams.frequencyPenalty
            logitBias = chatRequestParams.logitBias ?: model.defaultParams.logitBias
        }
    }

    private class StreamingAggregator {
        private val builder = StringBuilder()

        fun accept(delta: ChatResponse) {
            val chunk = delta.choices?.getOrNull(0)?.delta?.content
            if (!chunk.isNullOrEmpty()) {
                builder.append(chunk)
            }
        }

        fun result(): String = builder.toString()
    }

}
