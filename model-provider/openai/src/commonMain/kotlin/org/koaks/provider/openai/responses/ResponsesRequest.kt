package org.koaks.provider.openai.responses

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class ResponsesRequest(
    @SerialName("model") val model: String,
    @SerialName("input") val input: JsonElement,
    @SerialName("instructions") val instructions: String? = null,
    @SerialName("tools") val tools: List<JsonObject>? = null,
    @SerialName("text") val text: ResponsesText? = null,
    @SerialName("previous_response_id") val previousResponseId: String? = null,
    @SerialName("store") val store: Boolean? = null,
    @SerialName("stream") val stream: Boolean = true,
    @SerialName("include") val include: List<String>? = null,
    @SerialName("reasoning") val reasoning: JsonObject? = null,
    @SerialName("temperature") val temperature: Double? = null,
    @SerialName("top_p") val topP: Double? = null,
    @SerialName("max_output_tokens") val maxOutputTokens: Int? = null,
    @SerialName("parallel_tool_calls") val parallelToolCalls: Boolean? = null,
    @SerialName("truncation") val truncation: String? = null,
    @SerialName("background") val background: Boolean? = null,
    @SerialName("metadata") val metadata: JsonObject? = null,
)

@Serializable
data class ResponsesText(
    @SerialName("format") val format: JsonObject,
)

@Serializable
data class ResponsesCheckpointPayload(
    @SerialName("response_id") val responseId: String,
    @SerialName("mode") val mode: String,
)
