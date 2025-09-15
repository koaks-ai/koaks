package org.koaks.framework.entity.dsl

import org.koaks.framework.entity.Message

class TextMessageBuilder {
    private val parts = mutableListOf<Message>()

    fun userText(value: String) {
        parts += Message.userText(value)
    }

    fun assistantText(value: String?) {
        parts += Message.assistantText(value)
    }

    fun build(): MutableList<Message> = parts
}

fun textMessages(block: TextMessageBuilder.() -> Unit): MutableList<Message> {
    return TextMessageBuilder().apply(block).build()
}