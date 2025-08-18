package org.koaks.framework.entity.chat.responsesapi

import com.google.gson.annotations.SerializedName
import org.koaks.framework.toolcall.ToolDefinition

data class ResponsesRequest(

    @SerializedName("background")
    val background: Boolean? = null,

    @SerializedName("include")
    val include: List<String>? = null,

    @SerializedName("input")
    val input: List<InputItem>? = null,

    @SerializedName("instructions")
    val instructions: String? = null,

    @SerializedName("max_output_tokens")
    val maxOutputTokens: Int? = null,

    @SerializedName("max_tool_calls")
    val maxToolCalls: Int? = null,

    @SerializedName("metadata")
    val metadata: Map<String, String>? = null,

    @SerializedName("model")
    val model: String? = null,

    @SerializedName("parallel_tool_calls")
    val parallelToolCalls: Boolean? = null,

    @SerializedName("previous_response_id")
    val previousResponseId: String? = null,

    @SerializedName("prompt")
    val prompt: PromptConfig? = null,

    @SerializedName("prompt_cache_key")
    val promptCacheKey: String? = null,

    @SerializedName("reasoning")
    val reasoning: ReasoningConfig? = null,

    @SerializedName("safety_identifier")
    val safetyIdentifier: String? = null,

    @SerializedName("service_tier")
    val serviceTier: String? = null,

    @SerializedName("store")
    val store: Boolean? = null,

    @SerializedName("stream")
    val stream: Boolean? = null,

    @SerializedName("stream_options")
    val streamOptions: StreamOptions? = null,

    @SerializedName("temperature")
    val temperature: Double? = null,

    @SerializedName("text")
    val text: TextConfig? = null,

    @SerializedName("tool_choice")
    val toolChoice: ToolChoice? = null,

    @SerializedName("tools")
    val tools: List<ToolDefinition>? = null,

    @SerializedName("top_logprobs")
    val topLogprobs: Int? = null,

    @SerializedName("top_p")
    val topP: Double? = null,

    @SerializedName("truncation")
    val truncation: String? = null,

    @SerializedName("user")
    val user: String? = null // Deprecated
)

sealed class InputItem {
    data class TextInput(
        @SerializedName("type") val type: String = "text",
        @SerializedName("content") val content: String
    ) : InputItem()

    data class ImageInput(
        @SerializedName("type") val type: String = "image",
        @SerializedName("image_url") val imageUrl: String
    ) : InputItem()

    data class FileInput(
        @SerializedName("type") val type: String = "file",
        @SerializedName("file_id") val fileId: String
    ) : InputItem()
}

data class PromptConfig(
    @SerializedName("id") val id: String? = null,
    @SerializedName("variables") val variables: Map<String, String>? = null
)

data class ReasoningConfig(
    @SerializedName("effort") val effort: String? = null,
    @SerializedName("max_tokens") val maxTokens: Int? = null
)

data class StreamOptions(
    @SerializedName("include_usage") val includeUsage: Boolean? = null
)

data class TextConfig(
    @SerializedName("format") val format: String? = null
)

sealed class ToolChoice {
    data class Auto(
        @SerializedName("type") val type: String = "auto"
    ) : ToolChoice()

    data class Specific(
        @SerializedName("type") val type: String = "tool",
        @SerializedName("tool") val tool: String
    ) : ToolChoice()
}