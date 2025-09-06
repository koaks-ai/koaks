package org.koaks.framework.api.dsl

import org.koaks.framework.api.chat.ChatClient
import org.koaks.framework.context.KoaksContext
import org.koaks.framework.toolcall.toolinterface.Tool
import org.koaks.framework.toolcall.ToolManager
import org.koaks.framework.toolcall.ToolDefinition
import org.koaks.framework.toolcall.toDefinition


class ChatClientBuilder : BaseChatClientBuilder() {

    private var tools: MutableList<String> = mutableListOf()

    fun tools(block: CompletionToolBuilder.() -> Unit) {
        val builder = CompletionToolBuilder()
        builder.block()
        tools = builder.build()
        KoaksContext.registerToolList(clientId, tools)
    }

    fun build(): ChatClient<*, *> {
        return ChatClient(model, memory, clientId)
    }

}

class CompletionToolBuilder {
    private val actions: MutableList<() -> Unit> = mutableListOf()
    private var groupsAction: (() -> Unit)? = null
    private val toolNameList: MutableList<String> = mutableListOf()

    fun default() {
        actions += {
            ToolManager.getGroupToolList("default")?.let { tool ->
                toolNameList.addAll(tool.map { it.toolName })
            }
        }
    }

    fun groups(vararg names: String) {
        groupsAction = {
            names.forEach { group ->
                ToolManager.getGroupToolList(group)?.let { tool ->
                    toolNameList.addAll(tool.map { it.toolName })
                }
            }
        }
    }

    fun addTools(vararg tools: ToolDefinition) {
        actions += {
            toolNameList += tools.map { it.toolName }
        }
        // add to global tool container
        tools.forEach {
            ToolManager.registerTool(it)
        }
    }

    fun addTools(vararg tools: Tool<*>) {
        actions += {
            tools.map { it.toDefinition() }.forEach {
                toolNameList += it.toolName
            }
        }
        // add to global tool container
        tools.forEach {
            ToolManager.registerTool(it.toDefinition())
        }
    }

    fun build(): MutableList<String> {
        actions.forEach { it.invoke() }
        // make the group action is always last
        groupsAction?.invoke() ?: run {
            groups("default")
        }
        return toolNameList
    }
}

fun createChatClient(block: ChatClientBuilder.() -> Unit): ChatClient<*, *> {
    val builder = ChatClientBuilder()
    builder.block()
    return builder.build()
}
