package org.koaks.framework.model.adapter

import org.koaks.framework.entity.chat.ChatMessage
import org.koaks.framework.entity.inner.InnerChatRequest

interface ChatModelAdapter<TRequest, TResponse> {

    fun toInnerRequest(innerChatRequest: InnerChatRequest): TRequest

    fun toChatResponse(innerResponse: TResponse): ChatMessage

}