package org.koaks.provider.qwen

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.koaks.framework.entity.Message
import org.koaks.framework.toolcall.ToolDefinition

@Serializable
data class QwenChatRequest(

    @SerialName("model")
    var modelName: String?,

    @SerialName("messages")
    var messageList: MutableList<Message>?,

    @SerialName("tools")
    var tools: List<ToolDefinition>? = null,

    @SerialName("parallel_tool_calls")
    var parallelToolCalls: Boolean? = null,

    @SerialName("system_message")
    var systemMessage: String? = null,

    @SerialName("max_tokens")
    var maxTokens: Int? = null,

    @SerialName("temperature")
    var temperature: Double? = null,

    @SerialName("top_p")
    var topP: Double? = null,

    @SerialName("n")
    var n: Int? = null,

    @SerialName("stream")
    var stream: Boolean? = null,

    @SerialName("stop")
    var stop: String? = null,

    @SerialName("presence_penalty")
    var presencePenalty: Double? = null,

    @SerialName("frequency_penalty")
    var frequencyPenalty: Double? = null,

    @SerialName("logit_bias")
    var logitBias: String? = null,

    @SerialName("response_format")
    var responseFormat: Map<String, String>? = null

)