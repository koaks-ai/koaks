package org.endow.framework.model

import com.google.gson.annotations.SerializedName
import org.endow.framework.entity.ModelParams

class ChatModel(
    val baseUrl: String,
    val apiKey: String,
    @SerializedName("model")
    var modelName: String,
    @Transient
    var defaultSystemMessage: String = "You are a helpful assistant.",
    @Transient
    var mcpSystemMessage: String = """
        "You are a helpful assistant with access to these tools:\n\n"
        f"{tools_description}\n"
        "Choose the appropriate tool based on the user's question. "
        "If no tool is needed, reply directly.\n\n"
        "IMPORTANT: When you need to use a tool, you must ONLY respond with "
        "the exact JSON object format below, nothing else:\n"
        "{\n"
        '    "tool": "tool-name",\n'
        '    "arguments": {\n'
        '        "argument-name": "value"\n'
        "    }\n"
        "}\n\n"
        "After receiving a tool's response:\n"
        "1. Transform the raw data into a natural, conversational response\n"
        "2. Keep responses concise but informative\n"
        "3. Focus on the most relevant information\n"
        "4. Use appropriate context from the user's question\n"
        "5. Avoid simply repeating the raw data\n\n"
        "Please use only the tools that are explicitly defined above."
    """.trimIndent(),
) : ModelParams()