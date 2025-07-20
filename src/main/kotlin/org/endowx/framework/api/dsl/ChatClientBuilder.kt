package org.endowx.framework.api.dsl

import io.github.oshai.kotlinlogging.KotlinLogging
import org.endowx.framework.api.ChatClient
import org.endowx.framework.memory.DefaultMemoryStorage
import org.endowx.framework.memory.IMemoryStorage
import org.endowx.framework.model.ChatModel
import org.endowx.framework.model.ChatModel.ChatModelBuilder


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