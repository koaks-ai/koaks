package org.koaks.framework.api.dsl

import org.koaks.framework.api.chat.completions.ChatClient
import org.koaks.framework.api.chat.responses.ResponsesClient

fun createChatClient(block: ChatClientBuilder.() -> Unit): ChatClient {
    val builder = ChatClientBuilder()
    builder.block()
    return builder.build()
}

fun createResponsesClient(block: ResponsesClientBuilder.() -> Unit): ResponsesClient {
    val builder = ResponsesClientBuilder()
    builder.block()
    return builder.build()
}