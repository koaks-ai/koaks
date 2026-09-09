package org.koaks.runtime.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.koaks.framework.loop.AgentId
import org.koaks.framework.loop.ToolScope
import org.koaks.framework.loop.tool
import org.koaks.runtime.coordination.SendResult
import org.koaks.runtime.coordination.currentAgentContext

@Serializable
private class ListChildrenInput

@Serializable
private data class SendToChildInput(val agentId: String, val message: String)

/** Registers tools that resolve their parent identity at invocation time. */
fun ToolScope.childAgents() {
    tool<ListChildrenInput>("list_children", "List your direct child agents and their current tasks and states.") {
        val context = requireNotNull(currentAgentContext()) { "child tools require a runtime-managed run" }
        buildJsonArray {
            context.listChildren().forEach { child ->
                add(buildJsonObject {
                    put("agentId", JsonPrimitive(child.agentId.value))
                    put("name", JsonPrimitive(child.name))
                    put("summary", JsonPrimitive(child.summary))
                    put("state", JsonPrimitive(child.state.name.lowercase()))
                    put("acceptingMessages", JsonPrimitive(child.acceptingMessages))
                })
            }
        }.toString()
    }
    tool<SendToChildInput>("send_to_child", "Send a message to your active direct child by agentId. Accepted means queued for its next model request.", hasSideEffects = true) {
        val context = requireNotNull(currentAgentContext()) { "child tools require a runtime-managed run" }
        buildJsonObject {
            when (val result = context.sendTo(AgentId(it.agentId), it.message)) {
                is SendResult.Accepted -> {
                    put("status", JsonPrimitive("accepted"))
                    put("messageId", JsonPrimitive(result.messageId.toString()))
                }
                is SendResult.Rejected -> {
                    put("status", JsonPrimitive("rejected"))
                    put("reason", JsonPrimitive(result.reason))
                }
            }
        }.toString()
    }
}
