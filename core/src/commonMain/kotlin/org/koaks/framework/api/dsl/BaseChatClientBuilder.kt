package org.koaks.framework.api.dsl

import org.koaks.framework.memory.DefaultMemoryStorage
import org.koaks.framework.memory.IMemoryStorage
import org.koaks.framework.model.AbstractChatModel


abstract class BaseChatClientBuilder {
    protected lateinit var model: AbstractChatModel<*, *>
    protected var memory: IMemoryStorage = DefaultMemoryStorage

    fun model(block: ModelSelector.() -> Unit) {
        val selector = ModelSelector()
        selector.block()
        this.model = requireNotNull(selector.selected) {
            "no model selected"
        }
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
