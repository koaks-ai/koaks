package org.koaks.framework.api.dsl

import org.koaks.framework.memory.DefaultMemoryStorage
import org.koaks.framework.memory.IMemoryStorage
import org.koaks.framework.model.ChatModel
import org.koaks.framework.toolcall.ToolContainer
import org.koaks.framework.toolcall.ToolDefinition

abstract class BaseChatClientBuilder {
    protected lateinit var model: ChatModel
    protected var memory: IMemoryStorage = DefaultMemoryStorage
    protected var tools: List<ToolDefinition> = listOf()

    fun model(block: ChatModel.ChatModelBuilder.() -> Unit) {
        model = ChatModel.ChatModelBuilder().apply(block).build()
    }

    fun memory(block: MemoryBuilder.() -> Unit) {
        val builder = MemoryBuilder()
        builder.block()
        memory = builder.build()
    }

    fun tools(block: ToolBuilder.() -> Unit) {
        val builder = ToolBuilder()
        builder.block()
        tools = builder.build()
    }
}

class MemoryBuilder {
    private var storage: IMemoryStorage = DefaultMemoryStorage

    fun default() {
        storage = DefaultMemoryStorage
    }

    fun build(): IMemoryStorage = storage
}

class ToolBuilder {
    private val tools: MutableList<ToolDefinition> = mutableListOf()

    fun default() {
        tools += ToolContainer.getTools("default")
    }

    fun groups(vararg names: String) {
        names.forEach { name ->
            tools += ToolContainer.getTools(name)
        }
    }

    fun build(): List<ToolDefinition> = tools
}