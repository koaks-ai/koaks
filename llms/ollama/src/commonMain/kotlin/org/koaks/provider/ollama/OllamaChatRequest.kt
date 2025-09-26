package org.koaks.provider.ollama

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.koaks.framework.entity.AudioConfig
import org.koaks.framework.entity.Message
import org.koaks.framework.entity.StreamOptions
import org.koaks.framework.entity.enums.ModalitiesType
import org.koaks.framework.toolcall.ToolDefinition

@Serializable
data class OllamaChatRequest(

    @SerialName("model")
    var modelName: String?,

    @SerialName("messages")
    var messageList: MutableList<OllamaMessage>?,

    @SerialName("tools")
    var tools: List<ToolDefinition>? = null,

)