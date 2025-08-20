package org.koaks.framework.entity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.koaks.framework.entity.chat.ChatMessage

@Serializable
class Message {
    var id: String? = null
    var content: String? = null
    // TODO: add refusal
//    var refusal: Any? = null
    var role: String? = null

    @SerialName("tool_calls")
    var toolCalls: MutableList<ChatMessage.ToolCall>? = null

    @SerialName("function_call")
    var functionCall: ChatMessage.FunctionCall? = null

    var audio: String? = null

    @SerialName("tool_call_id")
    var toolCallID: String? = null

    constructor(role: String, content: String) {
        this.role = role
        this.content = content
    }

    constructor(role: String, content: String, toolCallID: String?) {
        this.role = role
        this.content = content
        this.toolCallID = toolCallID
    }

    override fun toString(): String {
        return "Message{" +
                "content='" + content + '\'' +
//                ", refusal=" + refusal +
                ", role='" + role + '\'' +
                ", toolCalls=" + toolCalls +
                ", functionCall=" + functionCall +
                ", audio='" + audio + '\'' +
                ", toolCallID='" + toolCallID + '\'' +
                '}'
    }

    companion object {
        fun of(role: String, content: String): Message {
            return Message(role, content)
        }

        fun system(content: String): Message {
            return Message("system", content)
        }

        fun assistant(content: String): Message {
            return Message("assistant", content)
        }

        fun user(content: String): Message {
            return Message("user", content)
        }

        fun tool(content: String, toolCallId: String): Message {
            return Message("tool", content, toolCallId)
        }

        fun developer(content: String): Message {
            return Message("developer", content)
        }
    }
}