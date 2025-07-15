package org.endow.framework.entity

import com.google.gson.annotations.SerializedName

class Message {
    //region Message getter and setter
    var content: String? = null
    var refusal: Any? = null
    var role: String? = null

    @SerializedName("tool_calls")
    private var toolCalls: MutableList<ChatResponse.ToolCall?>? = null

    @SerializedName("function_call")
    private var functionCall: ChatResponse.FunctionCall? = null
    var audio: String? = null


    @SerializedName("tool_call_id")
    private var toolCallID: String? = null

    constructor()

    constructor(role: String?, content: String?) {
        this.role = role
        this.content = content
    }

    constructor(role: String?, content: String?, toolCallID: String?) {
        this.role = role
        this.content = content
        this.toolCallID = toolCallID
    }

    fun getToolCalls(): MutableList<ChatResponse.ToolCall?>? {
        return toolCalls
    }

    fun setToolCalls(toolCalls: MutableList<ChatResponse.ToolCall?>?) {
        this.toolCalls = toolCalls
    }

    fun getFunctionCall(): ChatResponse.FunctionCall? {
        return functionCall
    }

    fun setFunctionCall(functionCall: ChatResponse.FunctionCall?) {
        this.functionCall = functionCall
    }

    //endregion
    override fun toString(): String {
        return "Message{" +
                "content='" + content + '\'' +
                ", refusal=" + refusal +
                ", role='" + role + '\'' +
                ", toolCalls=" + toolCalls +
                ", functionCall=" + functionCall +
                ", audio='" + audio + '\'' +
                ", toolCallID='" + toolCallID + '\'' +
                '}'
    }

    companion object {
        fun of(role: String?, content: String?): Message {
            return Message(role, content)
        }

        fun system(content: String?): Message {
            return Message("system", content)
        }

        fun assistant(content: String?): Message {
            return Message("assistant", content)
        }

        fun user(content: String?): Message {
            return Message("user", content)
        }

        fun tool(content: String?, toolCallID: String?): Message {
            return Message("tool", content, toolCallID)
        }
    }
}