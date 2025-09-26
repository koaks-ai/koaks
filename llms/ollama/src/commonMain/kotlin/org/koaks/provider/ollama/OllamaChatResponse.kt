package org.koaks.provider.ollama

import kotlinx.serialization.Serializable
import org.koaks.framework.model.ToolCallable

import kotlinx.serialization.SerialName

@Serializable
data class OllamaChatResponse(
    @SerialName("model")
    val model: String,

    @SerialName("created_at")
    val createdAt: String,

    @SerialName("message")
    val message: OllamaMessage? = null,

    @SerialName("done")
    val done: Boolean,

    @SerialName("total_duration")
    val totalDuration: Long? = null,

    @SerialName("load_duration")
    val loadDuration: Long? = null,

    @SerialName("prompt_eval_count")
    val promptEvalCount: Int? = null,

    @SerialName("prompt_eval_duration")
    val promptEvalDuration: Long? = null,

    @SerialName("eval_count")
    val evalCount: Int? = null,

    @SerialName("eval_duration")
    val evalDuration: Long? = null,

    @SerialName("error")
    val error: ErrorOutput? = null
) : ToolCallable {

    override fun shouldToolCall(): Boolean {
        return !message?.toolCalls.isNullOrEmpty()
    }

    @Serializable
    data class Message(
        @SerialName("role")
        val role: String,

        @SerialName("content")
        val content: String,

        @SerialName("images")
        val images: List<String>? = null
    )

    @Serializable
    data class ErrorOutput(
        @SerialName("code")
        val code: String? = null,

        @SerialName("param")
        val param: String? = null,

        @SerialName("message")
        val message: String? = null,

        @SerialName("type")
        val type: String? = null
    )
}