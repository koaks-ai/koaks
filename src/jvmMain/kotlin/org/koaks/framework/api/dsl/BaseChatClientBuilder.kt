package org.koaks.framework.api.dsl

import org.koaks.framework.memory.DefaultMemoryStorage
import org.koaks.framework.memory.IMemoryStorage
import org.koaks.framework.model.ChatModel


abstract class BaseChatClientBuilder {
    protected lateinit var model: ChatModel
    protected var memory: IMemoryStorage = DefaultMemoryStorage

    fun model(block: ChatModel.ChatModelBuilder.() -> Unit) {
        model = ChatModel.ChatModelBuilder().apply(block).build()
    }

    fun memory(block: MemoryBuilder.() -> Unit) {
        val builder = MemoryBuilder()
        builder.block()
        memory = builder.build()
    }

}

class MemoryBuilder {
    private var storage: IMemoryStorage = DefaultMemoryStorage

    fun default() {
        storage = DefaultMemoryStorage
    }

    fun custom(storage: IMemoryStorage) {
        this.storage = storage
    }

    fun build(): IMemoryStorage = storage
}
