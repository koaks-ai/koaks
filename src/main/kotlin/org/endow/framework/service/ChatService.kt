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
import org.endow.framework.toolcall.ToolDefinition
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
                logger.error { "Streaming is not supported for tools. Falling back to non-streaming mode." }
                request.tools = null
            }
            ModelResponse.fromStream(httpClient.postAsObjectStream<ChatResponse>(request))
        } else {
            var response =
                ModelResponse.fromResult(httpClient.postAsObject<ChatResponse>(request)) { ChatResponse() }
            val caller = ToolCaller()
            while (isToolCallResponse(response.response)) {
                response.response.choices?.firstOrNull()?.message?.let { messages.add(it) }
                val toolCalls = response.response.choices?.firstOrNull()?.message?.toolCalls
                val keys = mutableListOf<Any>()
                toolCalls?.forEach {
                    val toolname = it.function?.name!!
                    it.function?.arguments.apply {
                        val jsonObject: JsonObject = JsonUtil.fromJson<JsonObject>(this)
                        val toolEntity: ToolDefinition? = ToolContainer.getTool(toolname)
                        toolEntity?.function?.parameters?.properties?.forEach { key, property ->
                            if (jsonObject.has(key)) {
                                keys.add(jsonObject.get(key).toString())
                            }
                        }
                    }
                    logger.debug { "tool call: $toolname, args: $keys" }
                    messages.add(Message.tool(caller.call(toolname, keys.toTypedArray()), it.id!!))
                }
                response = ModelResponse.fromResult(httpClient.postAsObject<ChatResponse>(request)) { ChatResponse() }
            }
            response
        }
    }

    private fun isToolCallResponse(chatResponse: ChatResponse): Boolean {
        return (chatResponse.choices?.firstOrNull()?.finishReason == "tool_calls")
                && (chatResponse.choices?.firstOrNull()?.message?.toolCalls != null)
    }

}