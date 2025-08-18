package org.koaks.framework.api.dsl

import org.koaks.framework.api.chat.completions.ChatClient


class ChatClientBuilder : BaseChatClientBuilder() {

    fun build(): ChatClient {
        return ChatClient(model, memory, tools)
    }

}
