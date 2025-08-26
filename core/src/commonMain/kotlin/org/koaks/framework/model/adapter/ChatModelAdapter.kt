package org.koaks.framework.model.adapter

import org.koaks.framework.entity.chat.ChatResponse
import org.koaks.framework.entity.inner.InnerChatRequest

interface ChatModelAdapter<TRequest, TResponse> {

    fun toChatRequest(innerChatRequest: InnerChatRequest): TRequest

    fun toChatResponse(providerResponse: TResponse): ChatResponse

}