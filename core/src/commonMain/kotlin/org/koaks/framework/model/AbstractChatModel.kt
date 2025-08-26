package org.koaks.framework.model

import org.koaks.framework.entity.ModelParams
import org.koaks.framework.entity.chat.ChatResponse
import org.koaks.framework.entity.inner.InnerChatRequest

abstract class AbstractChatModel<TRequest, TResponse>(
    open val baseUrl: String,
    open val apiKey: String,
    open val modelName: String,
) : ModelParams() {

    abstract val typeAdapter: TypeAdapter<TRequest, TResponse>

    abstract fun toChatRequest(innerChatRequest: InnerChatRequest): TRequest

    abstract fun toChatResponse(providerResponse: TResponse): ChatResponse

}
