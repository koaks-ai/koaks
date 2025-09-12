package org.koaks.framework.model

import org.koaks.framework.entity.ModelParams
import org.koaks.framework.entity.chat.ChatResponse
import org.koaks.framework.entity.inner.FullChatRequest

abstract class AbstractChatModel<TRequest, TResponse>(
    open val baseUrl: String,
    open val apiKey: String,
    open val modelName: String,
) {

    val defaultParams = ModelParams()

    abstract val typeAdapter: TypeAdapter<TRequest, TResponse>

    abstract fun toChatRequest(fullChatRequest: FullChatRequest): TRequest

    abstract fun toChatResponse(providerResponse: TResponse): ChatResponse

}
