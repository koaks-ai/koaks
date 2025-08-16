package org.koaks.framework.api.dsl

import org.koaks.framework.api.ChatClient
import org.koaks.framework.memory.DefaultMemoryStorage
import org.koaks.framework.memory.IMemoryStorage
import org.koaks.framework.model.ChatModel
import org.koaks.framework.model.ChatModel.ChatModelBuilder


class ChatClientBuilder {

    private lateinit var model: ChatModel
    private var memory: IMemoryStorage = DefaultMemoryStorage

    fun model(block: ChatModelBuilder.() -> Unit) {
        model = ChatModelBuilder().apply(block).build()
    }

    fun memory(block: MemoryBuilder.() -> Unit) {
        val builder = MemoryBuilder()
        builder.block()
        memory = builder.build()
    }


    fun build(): ChatClient {
        return ChatClient(model, memory)
    }

}

class MemoryBuilder {
    private var storage: IMemoryStorage = DefaultMemoryStorage

    fun default() {
        storage = DefaultMemoryStorage
    }

    fun build(): IMemoryStorage = storage
}

fun createChatClient(block: ChatClientBuilder.() -> Unit): ChatClient {
    val builder = ChatClientBuilder()
    builder.block()
    return builder.build()
}