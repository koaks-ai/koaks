package org.koaks.framework.api.dsl

import org.koaks.framework.api.chat.responses.ResponsesClient


class ResponsesClientBuilder : BaseChatClientBuilder() {

    fun build(): ResponsesClient {
        return ResponsesClient(model, memory, tools)
    }

}
