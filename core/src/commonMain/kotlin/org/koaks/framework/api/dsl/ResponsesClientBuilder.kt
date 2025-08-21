package org.koaks.framework.api.dsl

import org.koaks.framework.api.chat.responses.ResponsesClient
import org.koaks.framework.toolcall.ToolContainer
import org.koaks.framework.toolcall.ToolDefinition


class ResponsesClientBuilder : BaseChatClientBuilder() {

    private var tools: List<ToolDefinition> = listOf()

    fun tools(block: ResponsesToolBuilder.() -> Unit) {
        val builder = ResponsesToolBuilder()
        builder.block()
        tools = builder.build()
    }

    fun build(): ResponsesClient {
        return ResponsesClient(model, memory, tools)
    }

}

class ResponsesToolBuilder {
    private val tools: MutableList<ToolDefinition> = mutableListOf()

    fun default() {
        tools += ToolContainer.getTools("default")
    }

    fun groups(vararg names: String) {
        names.forEach { name ->
            tools += ToolContainer.getTools(name)
        }
    }

    fun webSearch() {
        TODO("Not yet implemented")
    }

    fun fileSearch() {
        TODO("Not yet implemented")
    }

    fun computerUser() {
        TODO("Not yet implemented")
    }

    fun userLocation() {
        TODO("Not yet implemented")
    }

    fun build(): List<ToolDefinition> = tools
}