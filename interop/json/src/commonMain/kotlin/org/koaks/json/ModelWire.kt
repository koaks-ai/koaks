package org.koaks.json

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import okio.ByteString.Companion.decodeBase64
import org.koaks.framework.model.AgentError
import org.koaks.framework.model.Annotation
import org.koaks.framework.model.CheckpointScope
import org.koaks.framework.model.ContentPart
import org.koaks.framework.model.IncompleteReason
import org.koaks.framework.model.ItemRef
import org.koaks.framework.model.ModelEvent
import org.koaks.framework.model.ModelItem
import org.koaks.framework.model.ModelResponse
import org.koaks.framework.model.ProtocolId
import org.koaks.framework.model.ProviderCheckpoint
import org.koaks.framework.model.ProviderId
import org.koaks.framework.model.ProviderScopedId
import org.koaks.framework.model.ReplayPolicy
import org.koaks.framework.model.Role
import org.koaks.framework.model.ToolCall
import org.koaks.framework.model.TranscriptBasis
import org.koaks.framework.model.Usage

@Serializable
internal data class UsageWire(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0,
    @SerialName("cached_input_tokens") val cachedInputTokens: Int = 0,
    @SerialName("reasoning_output_tokens") val reasoningOutputTokens: Int = 0,
)

@Serializable
internal data class ProviderScopedIdWire(
    @SerialName("provider_id") val providerId: String,
    val raw: String,
)

@Serializable
internal data class ToolCallWire(
    val id: String,
    val name: String,
    @SerialName("arguments_json") val argumentsJson: String,
    @SerialName("native_id") val nativeId: ProviderScopedIdWire? = null,
    @SerialName("native_item_id") val nativeItemId: ProviderScopedIdWire? = null,
)

internal fun Usage.toWireJson(): JsonObject = wireJson.encodeToJsonElement(
    UsageWire.serializer(),
    UsageWire(promptTokens, completionTokens, totalTokens, cachedInputTokens, reasoningOutputTokens),
).jsonObject

internal fun JsonObject.toUsage(): Usage {
    val wire = wireJson.decodeFromJsonElement(UsageWire.serializer(), this)
    return Usage(
        promptTokens = wire.promptTokens,
        completionTokens = wire.completionTokens,
        totalTokens = wire.totalTokens,
        cachedInputTokens = wire.cachedInputTokens,
        reasoningOutputTokens = wire.reasoningOutputTokens,
    )
}

internal fun AgentError.toWireJson(): JsonObject = buildJsonObject {
    when (this@toWireJson) {
        is AgentError.ModelError -> {
            put("type", JsonPrimitive("model_error"))
            put("retriable", JsonPrimitive(retriable))
        }
        is AgentError.ToolError -> {
            put("type", JsonPrimitive("tool_error"))
            put("tool_name", JsonPrimitive(toolName))
            put("retriable", JsonPrimitive(retriable))
        }
        is AgentError.ParseError -> {
            put("type", JsonPrimitive("parse_error"))
            put("raw", JsonPrimitive(raw))
        }
        is AgentError.ToolNotFound -> {
            put("type", JsonPrimitive("tool_not_found"))
            put("tool_name", JsonPrimitive(toolName))
        }
        is AgentError.SkillError -> {
            put("type", JsonPrimitive("skill_error"))
            skillId?.let { put("skill_id", JsonPrimitive(it.value)) }
            put("stage", JsonPrimitive(stage.name.lowercase()))
        }
        is AgentError.PreparationError -> {
            put("type", JsonPrimitive("preparation_error"))
            put("component", JsonPrimitive(component))
        }
        is AgentError.Timeout -> {
            put("type", JsonPrimitive("timeout"))
            put("stage", JsonPrimitive(stage))
            put("elapsed_ms", JsonPrimitive(elapsedMs))
        }
        else -> put("type", JsonPrimitive("unknown_error"))
    }
    put("message", JsonPrimitive(message))
    cause?.message?.let { put("cause", JsonPrimitive(it)) }
}

internal fun IncompleteReason.toWireJson(): JsonObject = buildJsonObject {
    when (this@toWireJson) {
        IncompleteReason.MaxOutputTokens -> put("type", JsonPrimitive("max_output_tokens"))
        IncompleteReason.ContentFilter -> put("type", JsonPrimitive("content_filter"))
        IncompleteReason.Cancelled -> put("type", JsonPrimitive("cancelled"))
        is IncompleteReason.Other -> {
            put("type", JsonPrimitive("other"))
            put("code", JsonPrimitive(code))
        }
    }
}

internal fun JsonObject.toIncompleteReason(): IncompleteReason = when (requiredString("type")) {
    "max_output_tokens" -> IncompleteReason.MaxOutputTokens
    "content_filter" -> IncompleteReason.ContentFilter
    "cancelled" -> IncompleteReason.Cancelled
    "other" -> IncompleteReason.Other(requiredString("code"))
    else -> error("unknown incomplete reason '${requiredString("type")}'")
}

internal fun ToolCall.toWireJson(): JsonObject = wireJson.encodeToJsonElement(
    ToolCallWire.serializer(),
    ToolCallWire(
        id = id,
        name = name,
        argumentsJson = arguments,
        nativeId = nativeId?.toWire(),
        nativeItemId = nativeItemId?.toWire(),
    ),
).jsonObject

private fun ProviderScopedId.toWire() = ProviderScopedIdWire(providerId.value, raw)

internal fun JsonObject.toToolCall(): ToolCall {
    val value = wireJson.decodeFromJsonElement(ToolCallWire.serializer(), this)
    return ToolCall(
        id = value.id,
        name = value.name,
        arguments = value.argumentsJson,
        nativeId = value.nativeId?.toDomain(),
        nativeItemId = value.nativeItemId?.toDomain(),
    )
}

private fun ProviderScopedIdWire.toDomain() = ProviderScopedId(ProviderId(providerId), raw)

internal fun ProviderScopedId.toWireJson(): JsonObject = wireJson.encodeToJsonElement(
    ProviderScopedIdWire.serializer(),
    toWire(),
).jsonObject

internal fun JsonObject.toProviderScopedId(): ProviderScopedId =
    wireJson.decodeFromJsonElement(ProviderScopedIdWire.serializer(), this).toDomain()

internal fun ModelItem.toWireJson(): JsonObject = buildJsonObject {
    put("ref", JsonPrimitive(ref.value))
    nativeId?.let { put("native_id", it.toWireJson()) }
    when (this@toWireJson) {
        is ModelItem.Message -> {
            put("type", JsonPrimitive("message"))
            put("role", JsonPrimitive(role.name.lowercase()))
            put("content", buildJsonArray { content.forEach { add(it.toWireJson()) } })
            refusal?.let { put("refusal", JsonPrimitive(it)) }
            put("annotations", buildJsonArray { annotations.forEach { add(it.toWireJson()) } })
        }
        is ModelItem.ToolCall -> {
            put("type", JsonPrimitive("tool_call"))
            put("name", JsonPrimitive(name))
            put("arguments_json", JsonPrimitive(arguments))
            nativeItemId?.let { put("native_item_id", it.toWireJson()) }
        }
        is ModelItem.ToolResult -> {
            put("type", JsonPrimitive("tool_result"))
            put("call_ref", JsonPrimitive(callRef.value))
            put("output", JsonPrimitive(output))
            put("is_error", JsonPrimitive(isError))
        }
        is ModelItem.ReasoningSummary -> {
            put("type", JsonPrimitive("reasoning_summary"))
            put("text", JsonPrimitive(text))
        }
        is ModelItem.ProviderItem -> {
            put("type", JsonPrimitive("provider_item"))
            put("provider_id", JsonPrimitive(providerId.value))
            put("kind", JsonPrimitive(kind))
            put("display_text", JsonPrimitive(displayText))
            put("replay", JsonPrimitive(replay.name.lowercase()))
            put("payload_base64", JsonPrimitive(payload.base64()))
        }
    }
}

private fun ContentPart.toWireJson(): JsonObject = buildJsonObject {
    when (this@toWireJson) {
        is ContentPart.Text -> {
            put("type", JsonPrimitive("text"))
            put("text", JsonPrimitive(text))
        }
        is ContentPart.Image -> {
            put("type", JsonPrimitive("image"))
            url?.let { put("url", JsonPrimitive(it)) }
            base64?.let { put("base64", JsonPrimitive(it)) }
        }
        is ContentPart.Audio -> {
            put("type", JsonPrimitive("audio"))
            url?.let { put("url", JsonPrimitive(it)) }
            base64?.let { put("base64", JsonPrimitive(it)) }
            put("format", JsonPrimitive(format))
        }
    }
}

internal fun Annotation.toWireJson(): JsonObject = buildJsonObject {
    when (this@toWireJson) {
        is Annotation.UrlCitation -> {
            put("type", JsonPrimitive("url_citation"))
            put("url", JsonPrimitive(url))
            title?.let { put("title", JsonPrimitive(it)) }
            startIndex?.let { put("start_index", JsonPrimitive(it)) }
            endIndex?.let { put("end_index", JsonPrimitive(it)) }
        }
        is Annotation.FileCitation -> {
            put("type", JsonPrimitive("file_citation"))
            put("file_id", JsonPrimitive(fileId))
            filename?.let { put("filename", JsonPrimitive(it)) }
            startIndex?.let { put("start_index", JsonPrimitive(it)) }
            endIndex?.let { put("end_index", JsonPrimitive(it)) }
        }
        is Annotation.Generic -> {
            put("type", JsonPrimitive("generic"))
            put("kind", JsonPrimitive(kind))
            put("payload", JsonPrimitive(payload))
        }
    }
}

internal fun JsonObject.toModelItem(): ModelItem {
    val ref = optionalString("ref")?.let(::ItemRef) ?: ItemRef.generate()
    val nativeId = optionalObject("native_id")?.toProviderScopedId()
    return when (requiredString("type")) {
        "message" -> ModelItem.Message(
            ref = ref,
            nativeId = nativeId,
            role = Role.valueOf(requiredString("role").uppercase()),
            content = optionalArray("content").orEmpty().map { (it as? JsonObject ?: error("content item must be an object")).toContentPart() },
            refusal = optionalString("refusal"),
            annotations = optionalArray("annotations").orEmpty().map { (it as? JsonObject ?: error("annotation must be an object")).toAnnotation() },
        )
        "tool_call" -> ModelItem.ToolCall(
            ref, nativeId, requiredString("name"), requiredString("arguments_json"),
            optionalObject("native_item_id")?.toProviderScopedId(),
        )
        "tool_result" -> ModelItem.ToolResult(
            ref, nativeId, ItemRef(requiredString("call_ref")), requiredString("output"),
            optionalBoolean("is_error") ?: false,
        )
        "reasoning_summary" -> ModelItem.ReasoningSummary(ref, nativeId, requiredString("text"))
        "provider_item" -> ModelItem.ProviderItem(
            ref = ref,
            nativeId = nativeId,
            providerId = ProviderId(requiredString("provider_id")),
            kind = requiredString("kind"),
            displayText = requiredString("display_text"),
            replay = ReplayPolicy.valueOf(requiredString("replay").replaceFirstChar { it.uppercase() }),
            payload = requireNotNull(requiredString("payload_base64").decodeBase64()) { "invalid provider item payload_base64" },
        )
        else -> error("unknown model item type '${requiredString("type")}'")
    }
}

private fun JsonObject.toContentPart(): ContentPart = when (requiredString("type")) {
    "text" -> ContentPart.Text(requiredString("text"))
    "image" -> ContentPart.Image(optionalString("url"), optionalString("base64"))
    "audio" -> ContentPart.Audio(optionalString("url"), optionalString("base64"), requiredString("format"))
    else -> error("unknown content part type '${requiredString("type")}'")
}

internal fun JsonObject.toAnnotation(): Annotation = when (requiredString("type")) {
    "url_citation" -> Annotation.UrlCitation(requiredString("url"), optionalString("title"), optionalInt("start_index"), optionalInt("end_index"))
    "file_citation" -> Annotation.FileCitation(requiredString("file_id"), optionalString("filename"), optionalInt("start_index"), optionalInt("end_index"))
    "generic" -> Annotation.Generic(requiredString("kind"), requiredString("payload"))
    else -> error("unknown annotation type '${requiredString("type")}'")
}

internal fun ProviderCheckpoint.toWireJson(): JsonObject = buildJsonObject {
    put("provider_id", JsonPrimitive(providerId.value))
    put("codec_version", JsonPrimitive(codecVersion))
    put("basis", buildJsonObject {
        put("item_count", JsonPrimitive(basis.itemCount))
        put("digest", JsonPrimitive(basis.digest))
    })
    put("scope", JsonPrimitive(if (scope == CheckpointScope.InRun) "in_run" else "cross_turn"))
    put("payload_base64", JsonPrimitive(payload.base64()))
    expiresAtEpochMs?.let { put("expires_at_epoch_ms", JsonPrimitive(it)) }
}

internal fun JsonObject.toCheckpoint(): ProviderCheckpoint = ProviderCheckpoint(
    providerId = ProviderId(requiredString("provider_id")),
    codecVersion = optionalInt("codec_version") ?: error("'codec_version' is required"),
    basis = requiredObject("basis").let {
        TranscriptBasis(it.optionalInt("item_count") ?: error("'item_count' is required"), it.requiredString("digest"))
    },
    scope = when (requiredString("scope")) {
        "in_run" -> CheckpointScope.InRun
        "cross_turn" -> CheckpointScope.CrossTurn
        else -> error("unknown checkpoint scope '${requiredString("scope")}'")
    },
    payload = requireNotNull(requiredString("payload_base64").decodeBase64()) { "invalid checkpoint payload_base64" },
    expiresAtEpochMs = optionalLong("expires_at_epoch_ms"),
)

internal fun ModelEvent.toWireJson(): JsonObject = buildJsonObject {
    when (this@toWireJson) {
        is ModelEvent.Started -> {
            put("type", JsonPrimitive("started"))
            responseId?.let { put("response_id", JsonPrimitive(it)) }
        }
        is ModelEvent.CheckpointUpdated -> {
            put("type", JsonPrimitive("checkpoint_updated"))
            put("checkpoint", checkpoint.toWireJson())
        }
        is ModelEvent.TextDelta -> {
            put("type", JsonPrimitive("text_delta"))
            put("text", JsonPrimitive(text))
            itemRef?.let { put("item_ref", JsonPrimitive(it.value)) }
        }
        is ModelEvent.ReasoningDelta -> {
            put("type", JsonPrimitive("reasoning_delta"))
            put("text", JsonPrimitive(text))
            itemRef?.let { put("item_ref", JsonPrimitive(it.value)) }
            put("kind", JsonPrimitive(kind.name.lowercase()))
        }
        is ModelEvent.RefusalDelta -> {
            put("type", JsonPrimitive("refusal_delta"))
            put("text", JsonPrimitive(text))
            itemRef?.let { put("item_ref", JsonPrimitive(it.value)) }
        }
        is ModelEvent.AnnotationAdded -> {
            put("type", JsonPrimitive("annotation_added"))
            put("annotation", annotation.toWireJson())
            itemRef?.let { put("item_ref", JsonPrimitive(it.value)) }
        }
        is ModelEvent.ItemAdded -> {
            put("type", JsonPrimitive("item_added"))
            put("item", item.toWireJson())
        }
        is ModelEvent.ToolCallDelta -> {
            put("type", JsonPrimitive("tool_call_delta"))
            put("id", JsonPrimitive(id))
            index?.let { put("index", JsonPrimitive(it)) }
            nameDelta?.let { put("name_delta", JsonPrimitive(it)) }
            argumentsDelta?.let { put("arguments_delta", JsonPrimitive(it)) }
            itemRef?.let { put("item_ref", JsonPrimitive(it.value)) }
        }
        is ModelEvent.ToolCallCompleted -> {
            put("type", JsonPrimitive("tool_call_completed"))
            put("call", call.toWireJson())
        }
        is ModelEvent.ProviderEvent -> {
            put("type", JsonPrimitive("provider_event"))
            put("provider_id", JsonPrimitive(providerId.value))
            put("protocol_id", JsonPrimitive(protocolId.value))
            put("event_type", JsonPrimitive(type))
            put("source", JsonPrimitive(source.name.lowercase()))
            eventId?.let { put("event_id", JsonPrimitive(it)) }
            sequenceNumber?.let { put("sequence_number", JsonPrimitive(it)) }
            statusCode?.let { put("status_code", JsonPrimitive(it)) }
            contentType?.let { put("content_type", JsonPrimitive(it)) }
            put("payload", JsonPrimitive(payload))
        }
        is ModelEvent.Finished -> {
            put("type", JsonPrimitive("finished"))
            put("response", response.toWireJson())
        }
    }
}

internal fun JsonObject.toModelEvent(): ModelEvent = when (requiredString("type")) {
    "started" -> ModelEvent.Started(optionalString("response_id"))
    "checkpoint_updated" -> ModelEvent.CheckpointUpdated(requiredObject("checkpoint").toCheckpoint())
    "text_delta" -> ModelEvent.TextDelta(requiredString("text"), optionalString("item_ref")?.let(::ItemRef))
    "reasoning_delta" -> ModelEvent.ReasoningDelta(
        requiredString("text"),
        optionalString("item_ref")?.let(::ItemRef),
        when (optionalString("kind") ?: "raw") {
            "summary" -> ModelEvent.ReasoningKind.SUMMARY
            "raw" -> ModelEvent.ReasoningKind.RAW
            else -> error("unknown reasoning kind '${requiredString("kind")}'")
        },
    )
    "refusal_delta" -> ModelEvent.RefusalDelta(requiredString("text"), optionalString("item_ref")?.let(::ItemRef))
    "annotation_added" -> ModelEvent.AnnotationAdded(requiredObject("annotation").toAnnotation(), optionalString("item_ref")?.let(::ItemRef))
    "item_added" -> ModelEvent.ItemAdded(requiredObject("item").toModelItem())
    "tool_call_delta" -> ModelEvent.ToolCallDelta(
        requiredString("id"), optionalInt("index"), optionalString("name_delta"), optionalString("arguments_delta"),
        optionalString("item_ref")?.let(::ItemRef),
    )
    "tool_call_completed" -> ModelEvent.ToolCallCompleted(requiredObject("call").toToolCall())
    "provider_event" -> ModelEvent.ProviderEvent(
        providerId = ProviderId(requiredString("provider_id")),
        type = requiredString("event_type"),
        payload = requiredString("payload"),
        protocolId = ProtocolId(optionalString("protocol_id") ?: requiredString("provider_id")),
        source = when (optionalString("source") ?: "sse") {
            "sse" -> ModelEvent.ProviderEventSource.SSE
            "body" -> ModelEvent.ProviderEventSource.BODY
            "ndjson" -> ModelEvent.ProviderEventSource.NDJSON
            "http_error" -> ModelEvent.ProviderEventSource.HTTP_ERROR
            else -> error("unknown provider event source '${requiredString("source")}'")
        },
        eventId = optionalString("event_id"),
        sequenceNumber = optionalLong("sequence_number"),
        statusCode = optionalInt("status_code"),
        contentType = optionalString("content_type"),
    )
    "finished" -> ModelEvent.Finished(requiredObject("response").toModelResponse())
    else -> error("model event replacement type '${requiredString("type")}' is not supported")
}

private fun ModelResponse.toWireJson(): JsonObject = buildJsonObject {
    when (this@toWireJson) {
        is ModelResponse.Completed -> put("status", JsonPrimitive("completed"))
        is ModelResponse.Incomplete -> {
            put("status", JsonPrimitive("incomplete"))
            put("reason", reason.toWireJson())
        }
        is ModelResponse.Failed -> {
            put("status", JsonPrimitive("failed"))
            put("error", error.toWireJson())
        }
    }
    id?.let { put("id", JsonPrimitive(it)) }
    put("output", buildJsonArray { output.forEach { add(it.toWireJson()) } })
    put("usage", usage.toWireJson())
    checkpoint?.let { put("checkpoint", it.toWireJson()) }
}

private fun JsonObject.toModelResponse(): ModelResponse {
    val id = optionalString("id")
    val output = optionalArray("output").orEmpty().map { (it as? JsonObject ?: error("model response output must be an object")).toModelItem() }
    val usage = optionalObject("usage")?.toUsage() ?: Usage.ZERO
    val checkpoint = optionalObject("checkpoint")?.toCheckpoint()
    return when (requiredString("status")) {
        "completed" -> ModelResponse.Completed(id, output, usage, checkpoint)
        "incomplete" -> ModelResponse.Incomplete(
            id = id,
            reason = requiredObject("reason").toIncompleteReason(),
            output = output,
            usage = usage,
            checkpoint = checkpoint,
        )
        "failed" -> ModelResponse.Failed(
            error = requiredObject("error").let {
                AgentError.ModelError(it.requiredString("message"), it.optionalBoolean("retriable") ?: false)
            },
            id = id,
            output = output,
            usage = usage,
            checkpoint = checkpoint,
        )
        else -> error("unknown model response status '${requiredString("status")}'")
    }
}
