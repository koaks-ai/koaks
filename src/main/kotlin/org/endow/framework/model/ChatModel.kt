package org.endow.framework.model

import com.google.gson.annotations.SerializedName
import org.endow.framework.entity.ModelParams

class ChatModel(
    val baseUrl: String,
    val apiKey: String,
    @SerializedName("model")
    var modelName: String,
    @Transient
    var defaultSystemMessage: String = DEFAULT_SYSTEM_MESSAGE,
    @Transient
    var mcpSystemMessage: String = DEFAULT_MCP_SYSTEM_MESSAGE
) : ModelParams() {

    companion object {
        const val DEFAULT_MCP_SYSTEM_MESSAGE = """
            You are a helpful assistant with access to these tools:

            {tools_description}
            Choose the appropriate tool based on the user's question. 
            If no tool is needed, reply directly.

            IMPORTANT: When you need to use a tool, you must ONLY respond with 
            the exact JSON object format below, nothing else:
            {
                "tool": "tool-name",
                "arguments": {
                    "argument-name": "value"
                }
            }

            After receiving a tool's response:
            1. Transform the raw data into a natural, conversational response
            2. Keep responses concise but informative
            3. Focus on the most relevant information
            4. Use appropriate context from the user's question
            5. Avoid simply repeating the raw data
        """

        const val DEFAULT_SYSTEM_MESSAGE = """
            You are a helpful assistant.
        """
    }

    class ChatModelBuilder {
        var baseUrl: String? = null
        var apiKey: String? = null
        var modelName: String? = null
        var defaultSystemMessage: String = ChatModel.DEFAULT_SYSTEM_MESSAGE
        var mcpSystemMessage: String = ChatModel.DEFAULT_MCP_SYSTEM_MESSAGE

        fun build(): ChatModel {
            return ChatModel(
                baseUrl = requireNotNull(baseUrl) { "baseUrl is required" },
                apiKey = requireNotNull(apiKey) { "apiKey is required" },
                modelName = requireNotNull(modelName) { "modelName is required" },
                defaultSystemMessage = defaultSystemMessage,
                mcpSystemMessage = mcpSystemMessage
            )
        }
    }
}

