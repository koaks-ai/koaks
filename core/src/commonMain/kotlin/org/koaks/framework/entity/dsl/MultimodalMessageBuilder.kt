package org.koaks.framework.entity.dsl

import org.koaks.framework.entity.Message

class MultimodalMessageBuilder {
    private val parts = mutableListOf<Message>()

    fun userText(value: String) {
        parts += Message.userText(value)
    }

    fun assistantText(value: String?) {
        parts += Message.assistantText(value)
    }

    fun userImageUrl(url: String) {
        parts += Message.userImageUrl(url)
    }

    fun assistantImageUrl(url: String) {
        parts += Message.assistantImageUrl(url)
    }

    fun userImageBase64(base64: String) {
        parts += Message.userImageBase64(base64)
    }

    fun assistantImageBase64(base64: String) {
        parts += Message.assistantImageBase64(base64)
    }

    fun userVideoUrl(url: String) {
        parts += Message.userVideoUrl(url)
    }

    fun assistantVideoUrl(url: String) {
        parts += Message.assistantVideoUrl(url)
    }

    fun userVideoFrames(vararg frames: String) {
        parts += Message.userVideoFrame(*frames)
    }

    fun assistantVideoFrames(vararg frames: String) {
        parts += Message.assistantVideoFrame(*frames)
    }

    fun userAudio(url: String, format: String) {
        parts += Message.userAudio(url, format)
    }

    fun assistantAudio(url: String, format: String) {
        parts += Message.assistantAudio(url, format)
    }

    fun build(): Message = Message.multimodal(*parts.toTypedArray())
}

fun multimodalMessage(block: MultimodalMessageBuilder.() -> Unit): Message {
    return MultimodalMessageBuilder().apply(block).build()
}