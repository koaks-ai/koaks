package org.endow.framework.api.dsl

import org.endow.framework.api.ChatClient
import org.endow.framework.memory.DefaultMemoryStorage
import org.endow.framework.memory.IMemoryStorage
import org.endow.framework.model.ChatModel
import org.endow.framework.model.ChatModelBuilder

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