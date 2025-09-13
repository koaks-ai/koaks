package org.koaks.framework.entity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.koaks.framework.entity.chat.ChatResponse
import org.koaks.framework.entity.enums.MessageRole
import org.koaks.framework.utils.json.ContentListSerializer

@Serializable
data class Message(
    @SerialName("id")
    var id: String? = null,

    @SerialName("content")
    @Serializable(with = ContentListSerializer::class)
    var content: List<ContentItem>,

    @SerialName("role")
    var role: MessageRole,

    @SerialName("tool_calls")
    var toolCalls: MutableList<ChatResponse.ToolCall>? = null,

    @SerialName("function_call")
    var functionCall: ChatResponse.FunctionCall? = null,

    @SerialName("audio")
    var audio: String? = null,

    @SerialName("tool_call_id")
    var toolCallId: String? = null
) {
    companion object {
        fun system(text: String) = Message(
            role = MessageRole.SYSTEM,
            content = listOf(ContentItem.Text(text))
        )

        fun assistantText(text: String?) = Message(
            role = MessageRole.ASSISTANT,
            content = listOf(ContentItem.Text(text))
        )

        fun tool(content: String, toolCallId: String) = Message(
            role = MessageRole.TOOL,
            content = listOf(ContentItem.Text(content)),
        )

        fun userText(text: String) = Message(
            role = MessageRole.USER,
            content = listOf(ContentItem.Text(text))
        )

        fun userImage(url: String, desc: String? = null) = Message(
            role = MessageRole.USER,
            content = listOfNotNull(
                ContentItem.Image(url),
                desc?.let { ContentItem.Text(it) }
            )
        )

        fun userVideo(frames: List<String>, desc: String? = null) = Message(
            role = MessageRole.USER,
            content = listOfNotNull(
                ContentItem.Video(frames),
                desc?.let { ContentItem.Text(it) }
            )
        )

        fun multimodal(vararg message: Message) = Message(
            role = MessageRole.USER,
            content = message.flatMap { it.content }
        )
    }
}