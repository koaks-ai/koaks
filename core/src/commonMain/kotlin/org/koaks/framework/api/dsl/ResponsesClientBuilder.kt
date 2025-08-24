package org.koaks.framework.api.dsl

import org.koaks.framework.api.chat.responses.ResponsesClient
import org.koaks.framework.toolcall.toolinterface.Tool
import org.koaks.framework.toolcall.ToolManager
import org.koaks.framework.toolcall.ToolDefinition
import org.koaks.framework.toolcall.toDefinition


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

    private val actions: MutableList<() -> Unit> = mutableListOf()
    private var groupsAction: (() -> Unit)? = null
    private val toolList: MutableList<ToolDefinition> = mutableListOf()

    fun default() {
        actions += { toolList += ToolManager.getTools("default") }
    }

    fun groups(vararg names: String) {
        groupsAction = {
            toolList += ToolManager.getTools(*names)
        }
    }

    fun addTools(vararg tools: ToolDefinition) {
        actions += {
            toolList += tools
            tools.forEach { ToolManager.registerTool(it) }
        }
    }

    fun addTools(vararg tools: Tool<*>) {
        actions += {
            tools.map { it.toDefinition() }.forEach {
                toolList += it
                ToolManager.registerTool(it)
            }
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

    fun build(): List<ToolDefinition> {
        actions.forEach { it.invoke() }
        groupsAction?.invoke() ?: run {
            groups("default")
        }
        return toolList
    }
}

fun createResponsesClient(block: ResponsesClientBuilder.() -> Unit): ResponsesClient {
    val builder = ResponsesClientBuilder()
    builder.block()
    return builder.build()
}