package org.koaks.provider.ollama

import io.github.oshai.kotlinlogging.KotlinLogging
import org.koaks.framework.entity.ContentItem
import org.koaks.framework.entity.Message
import org.koaks.framework.entity.chat.ChatResponse
import org.koaks.framework.entity.inner.FullChatRequest
import org.koaks.framework.model.AbstractChatModel
import org.koaks.framework.model.TypeAdapter

class OllamaChatModel(
    override val baseUrl: String,
    override val apiKey: String,
    override var modelName: String,
) : AbstractChatModel<OllamaChatRequest, OllamaChatResponse>(baseUrl, apiKey, modelName) {

    private val logger = KotlinLogging.logger {}

    override val typeAdapter = TypeAdapter(
        serializer = OllamaChatRequest.serializer(),
        deserializer = OllamaChatResponse.serializer(),
    )

    override fun toChatRequest(fullChatRequest: FullChatRequest): OllamaChatRequest =
        OllamaChatRequest(
            modelName = fullChatRequest.modelName,
            messageList = fullChatRequest.messages.map { msg ->
                val textBuilder = StringBuilder()
                val images = mutableListOf<String>()

                msg.content.forEach { item ->
                    when (item) {
                        is ContentItem.Text -> {
                            item.text?.let { textBuilder.append(it) }
                        }

                        is ContentItem.Image -> {
                            images.add(item.imagePath.url.split("data:image/png;base64,")[1])
                        }

                        else -> logger.error { "unsupported $item." }
                    }
                }

                OllamaMessage(
                    id = msg.id,
                    content = textBuilder.toString(),
                    images = images,
                    role = msg.role,
                    toolCalls = msg.toolCalls,
                )
            }.toMutableList()
        )

    // TODO: 待完善
    override fun toChatResponse(providerResponse: OllamaChatResponse): ChatResponse {
        val message = providerResponse.message

        val choice = message?.let {
            ChatResponse.Choice(
                message = Message(
                    role = it.role,
                    content = listOf(ContentItem.Text(it.content)),
                ),
                text = it.content,
                index = 0,
                finishReason = if (providerResponse.done) "stop" else null
            )
        }

        val usage = if (providerResponse.promptEvalCount != null || providerResponse.evalCount != null) {
            ChatResponse.Usage(
                promptTokens = providerResponse.promptEvalCount,
                completionTokens = providerResponse.evalCount,
                totalTokens = listOfNotNull(
                    providerResponse.promptEvalCount,
                    providerResponse.evalCount
                ).sum()
            )
        } else null

        return ChatResponse(
            shouldToolCall = providerResponse.shouldToolCall(),
            choices = choice?.let { listOf(it) },
            created = 0,// TODO: 待完善
            model = providerResponse.model,
            usage = usage,
            error = providerResponse.error?.let {
                ChatResponse.ErrorOutput(
                    code = it.code,
                    param = it.param,
                    message = it.message,
                    type = it.type
                )
            }
        )
    }

}