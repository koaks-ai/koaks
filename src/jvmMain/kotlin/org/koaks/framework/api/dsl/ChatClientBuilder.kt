package org.koaks.framework.api.dsl

import org.koaks.framework.api.chat.completions.ChatClient
import org.koaks.framework.toolcall.ToolContainer
import org.koaks.framework.toolcall.ToolDefinition


class ChatClientBuilder : BaseChatClientBuilder() {

    private var tools: List<ToolDefinition> = listOf()

    fun build(): ChatClient {
        return ChatClient(model, memory, tools)
    }

    fun tools(block: CompletionToolBuilder.() -> Unit) {
        val builder = CompletionToolBuilder()
        builder.block()
        tools = builder.build()
    }

}

class CompletionToolBuilder {
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
