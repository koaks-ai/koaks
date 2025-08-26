package org.koaks.framework.model.adapter

import org.koaks.framework.entity.chat.ChatMessage
import org.koaks.framework.entity.inner.InnerChatRequest

interface ChatModelAdapter<TRequest, TResponse> {

    fun toChatRequest(innerChatRequest: InnerChatRequest): TRequest

    fun toChatMessage(innerResponse: TResponse): ChatMessage

}