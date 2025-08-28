package org.koaks.framework.api.dsl

import org.koaks.framework.api.chat.ChatClient
import org.koaks.framework.toolcall.toolinterface.Tool
import org.koaks.framework.toolcall.ToolManager
import org.koaks.framework.toolcall.ToolDefinition
import org.koaks.framework.toolcall.toDefinition


class ChatClientBuilder : BaseChatClientBuilder() {

    private var tools: List<ToolDefinition> = listOf()

    fun tools(block: CompletionToolBuilder.() -> Unit) {
        val builder = CompletionToolBuilder()
        builder.block()
        tools = builder.build()
        model.toolContainer.putAll(tools.associateBy { it.toolName })
    }

    fun build(): ChatClient<*, *> {
        return ChatClient(model, memory)
    }

}

class CompletionToolBuilder {
    private val actions: MutableList<() -> Unit> = mutableListOf()
    private var groupsAction: (() -> Unit)? = null
    private val toolList: MutableList<ToolDefinition> = mutableListOf()

    fun default() {
        actions += {
            ToolManager.getGroupToolList("default")?.let {
                toolList.addAll(it)
            }
        }
    }

    fun groups(vararg names: String) {
        groupsAction = {
            names.forEach {
                ToolManager.getGroupToolList(it)?.let {
                    toolList.addAll(it)
                }
            }
        }
    }

    fun addTools(vararg tools: ToolDefinition) {
        actions += {
            toolList += tools
        }
    }

    fun addTools(vararg tools: Tool<*>) {
        actions += {
            tools.map { it.toDefinition() }.forEach {
                toolList += it
            }
        }
    }

    fun addToolsToGlobal(vararg tools: Tool<*>) {
        actions += {
            tools.map { it.toDefinition() }.forEach {
                toolList += it
                ToolManager.registerTool(it)
            }
        }
    }

    fun build(): List<ToolDefinition> {
        actions.forEach { it.invoke() }
        // make the group action is always last
        groupsAction?.invoke() ?: run {
            groups("default")
        }
        return toolList
    }
}

fun createChatClient(block: ChatClientBuilder.() -> Unit): ChatClient<*, *> {
    val builder = ChatClientBuilder()
    builder.block()
    return builder.build()
}
