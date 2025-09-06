package org.koaks.framework.api.dsl

import org.koaks.framework.memory.DefaultMemoryStorage
import org.koaks.framework.memory.IMemoryStorage
import org.koaks.framework.model.AbstractChatModel
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid


abstract class BaseChatClientBuilder {
    protected lateinit var model: AbstractChatModel<*, *>
    protected var memory: IMemoryStorage = DefaultMemoryStorage

    @OptIn(ExperimentalUuidApi::class)
    protected val clientId: String = Uuid.random().toString()

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
