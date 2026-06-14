package org.koaks.framework.model

import kotlinx.serialization.Serializable

/**
 * The unified, immutable message model. Every layer (memory, loop, provider)
 * speaks this type; providers translate it to/from their own wire format.
 *
 * @property role who produced the message.
 * @property parts the ordered content parts (text / image / tool calls / tool results).
 * @property id optional provider-assigned identifier.
 */
@Serializable
data class Message(
    val role: Role,
    val parts: List<ContentPart>,
    val id: String? = null,
) {
    /** Concatenated text of all [ContentPart.Text] parts. */
    val text: String
        get() = parts.filterIsInstance<ContentPart.Text>().joinToString("") { it.text }

    /** Tool calls carried by this (assistant) message, in order. */
    val toolCalls: List<ToolCall>
        get() = parts.filterIsInstance<ContentPart.ToolCallPart>().map { it.call }

    companion object {
        fun system(text: String): Message =
            Message(Role.SYSTEM, listOf(ContentPart.Text(text)))

        fun user(text: String): Message =
            Message(Role.USER, listOf(ContentPart.Text(text)))

        fun assistant(text: String, toolCalls: List<ToolCall> = emptyList()): Message =
            Message(
                role = Role.ASSISTANT,
                parts = buildList {
                    if (text.isNotEmpty()) add(ContentPart.Text(text))
                    toolCalls.forEach { add(ContentPart.ToolCallPart(it)) }
                },
            )

        fun tool(callId: String, output: String, isError: Boolean = false): Message =
            Message(
                role = Role.TOOL,
                parts = listOf(ContentPart.ToolResultPart(callId, output, isError)),
            )
    }
}
