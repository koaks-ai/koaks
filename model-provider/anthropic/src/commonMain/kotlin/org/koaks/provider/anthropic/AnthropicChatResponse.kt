package org.koaks.provider.anthropic

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A single Anthropic Messages API stream event, modeled as one all-optional class
 * (mirrors OpenAIChatResponse's single-class style) decoded with `ignoreUnknownKeys`.
 *
 * The stream is a sequence of typed SSE events; [type] selects which fields are set:
 *  - `message_start`     → [message] (carries input-token [Usage])
 *  - `content_block_start` → [contentBlock] (`tool_use` carries id + name) at [index]
 *  - `content_block_delta` → [delta] (`text_delta` / `thinking_delta` / `input_json_delta`)
 *  - `message_delta`     → top-level [usage] (output tokens)
 *  - `error`             → [error]
 *  - `content_block_stop` / `message_stop` / `ping` → no payload of interest
 */
@Serializable
data class AnthropicChatResponse(
    @SerialName("type") val type: String? = null,
    @SerialName("index") val index: Int? = null,
    @SerialName("message") val message: Message? = null,
    @SerialName("content_block") val contentBlock: ContentBlock? = null,
    @SerialName("delta") val delta: Delta? = null,
    @SerialName("usage") val usage: Usage? = null,
    @SerialName("error") val error: ErrorOutput? = null,
) {
    @Serializable
    data class Message(
        @SerialName("usage") val usage: Usage? = null,
    )

    @Serializable
    data class ContentBlock(
        @SerialName("type") val type: String? = null,
        @SerialName("id") val id: String? = null,
        @SerialName("name") val name: String? = null,
    )

    @Serializable
    data class Delta(
        @SerialName("type") val type: String? = null,
        @SerialName("text") val text: String? = null,
        @SerialName("thinking") val thinking: String? = null,
        @SerialName("partial_json") val partialJson: String? = null,
    )

    @Serializable
    data class Usage(
        @SerialName("input_tokens") val inputTokens: Int? = null,
        @SerialName("output_tokens") val outputTokens: Int? = null,
    )

    @Serializable
    data class ErrorOutput(
        @SerialName("type") val type: String? = null,
        @SerialName("message") val message: String? = null,
    )
}
