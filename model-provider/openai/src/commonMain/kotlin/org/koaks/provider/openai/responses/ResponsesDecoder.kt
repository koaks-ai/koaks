package org.koaks.provider.openai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.ByteString.Companion.encodeUtf8
import org.koaks.framework.model.AgentError
import org.koaks.framework.model.Annotation
import org.koaks.framework.model.CheckpointScope
import org.koaks.framework.model.IncompleteReason
import org.koaks.framework.model.ItemRef
import org.koaks.framework.model.ModelEvent
import org.koaks.framework.model.ModelItem
import org.koaks.framework.model.ModelResponse
import org.koaks.framework.model.ProviderId
import org.koaks.framework.model.ProviderScopedId
import org.koaks.framework.model.ReplayPolicy
import org.koaks.framework.model.ToolCall
import org.koaks.framework.model.TranscriptBasis
import org.koaks.framework.model.Usage
import org.koaks.framework.model.toDispatchCall
import org.koaks.framework.provider.WireDecoder
import org.koaks.framework.transport.WireFrame
import org.koaks.framework.utils.json.JsonUtil

class ResponsesDecoder(
    private val mode: ResponsesStateMode,
    private val persistCheckpoint: Boolean,
    private val basisItems: List<ModelItem>,
    private val codec: ResponsesCheckpointCodec = ResponsesCheckpointCodec(),
) : WireDecoder {

    private class ToolAcc(
        var callId: String? = null,
        var nativeItemId: String? = null,
        val name: StringBuilder = StringBuilder(),
        val args: StringBuilder = StringBuilder(),
        val ref: ItemRef = ItemRef.generate("call"),
    )

    private val tools = LinkedHashMap<Int, ToolAcc>()
    private val output = mutableListOf<ModelItem>()
    private val seenRefs = HashSet<String>()
    private val text = StringBuilder()
    private val refusal = StringBuilder()
    private val annotations = mutableListOf<Annotation>()
    private var usage: Usage = Usage.ZERO
    private var responseId: String? = null
    private var failed: AgentError.ModelError? = null
    private var incomplete: IncompleteReason? = null
    private var finished = false
    private var started = false
    private var textRef: ItemRef = ItemRef.generate("msg")

    override fun accept(frame: WireFrame): List<ModelEvent> {
        return when (frame) {
            is WireFrame.HttpError -> {
                failed = decodeHttpError(frame)
                finishFailed()
            }
            is WireFrame.Sse -> acceptEvent(frame.event, frame.data)
            is WireFrame.Body -> acceptCompletedJson(frame.text)
            is WireFrame.Ndjson -> acceptEvent(null, frame.line)
        }
    }

    internal fun acceptEvent(event: String?, data: String): List<ModelEvent> {
        if (data.isBlank() || data == "[DONE]") return emptyList()
        val obj = runCatching { JsonUtil.json.parseToJsonElement(data).jsonObject }.getOrElse { return emptyList() }
        return dispatch(event ?: obj.str("type"), obj)
    }

    private fun acceptCompletedJson(text: String): List<ModelEvent> {
        val obj = runCatching { JsonUtil.json.parseToJsonElement(text).jsonObject }.getOrElse { return emptyList() }
        return dispatch("response.completed", wrapResponse(obj))
    }

    private fun wrapResponse(obj: JsonObject): JsonObject =
        if (obj["response"] is JsonObject) obj else buildJsonObject { put("response", obj) }

    private fun dispatch(event: String?, obj: JsonObject): List<ModelEvent> {
        val events = mutableListOf<ModelEvent>()
        when (event) {
            "response.created", "response.in_progress", "response.queued" -> {
                readResponseId(obj)
                if (!started) {
                    started = true
                    events += ModelEvent.Started(responseId)
                }
            }
            "response.output_text.delta" -> {
                val delta = obj.str("delta").orEmpty()
                if (delta.isNotEmpty()) {
                    text.append(delta)
                    events += ModelEvent.TextDelta(delta, textRef)
                }
            }
            "response.refusal.delta" -> obj.str("delta")?.let { refusal.append(it) }
            "response.reasoning_summary_text.delta", "response.reasoning.delta" -> {
                val delta = obj.str("delta").orEmpty()
                if (delta.isNotEmpty()) events += ModelEvent.ReasoningDelta(delta)
            }
            "response.function_call_arguments.delta" -> {
                val index = obj.int("output_index") ?: 0
                val acc = tools.getOrPut(index) { ToolAcc() }
                obj.str("item_id")?.let { acc.nativeItemId = it }
                val fragment = obj.str("delta").orEmpty()
                acc.args.append(fragment)
                events += ModelEvent.ToolCallDelta(
                    id = acc.callId ?: acc.nativeItemId ?: acc.ref.value,
                    index = index,
                    argumentsDelta = fragment,
                    itemRef = acc.ref,
                )
            }
            "response.output_item.added" -> {
                val item = obj.obj("item") ?: return events
                when (item.str("type")) {
                    ResponsesItemTypes.FUNCTION_CALL, ResponsesItemTypes.CUSTOM_TOOL_CALL -> {
                        val index = obj.int("output_index") ?: tools.size
                        val acc = tools.getOrPut(index) { ToolAcc() }
                        item.str("call_id")?.let { acc.callId = it }
                        item.str("id")?.let { acc.nativeItemId = it }
                        item.str("name")?.let { acc.name.append(it) }
                        item.str("arguments")?.let { acc.args.append(it) }
                    }
                    ResponsesItemTypes.MESSAGE -> item.str("id")?.let {
                        textRef = ItemRef.generate("msg")
                    }
                    else -> Unit
                }
            }
            "response.output_item.done" -> {
                val item = obj.obj("item") ?: return events
                mapOutputItem(item)?.let { mapped ->
                    if (seenRefs.add(mapped.ref.value)) {
                        output += mapped
                        events += ModelEvent.ItemAdded(mapped)
                        if (mapped is ModelItem.ToolCall) {
                            events += ModelEvent.ToolCallCompleted(mapped.toDispatchCall())
                        }
                    }
                }
            }
            "response.output_text.annotation.added" -> {
                obj.obj("annotation")?.let { annotations += mapAnnotation(it) }
            }
            "response.completed" -> {
                ingestTerminal(obj)
            }
            "response.incomplete" -> {
                ingestTerminal(obj)
                incomplete = mapIncomplete(obj)
            }
            "response.failed", "error" -> {
                failed = decodeModelError(obj)
                return events + finishFailed()
            }
            else -> {
                if (event != null) {
                    events += ModelEvent.ProviderEvent(ProviderId.OpenAIResponses, event, obj.toString())
                }
            }
        }
        return events
    }

    override fun finish(): List<ModelEvent> {
        if (finished) return emptyList()
        if (failed != null) return finishFailed()
        val events = mutableListOf<ModelEvent>()
        flushAssistantIfMissing()
        tools.entries.sortedBy { it.key }.forEach { (_, acc) ->
            if (output.any { it.ref == acc.ref }) return@forEach
            if (acc.name.isEmpty() && acc.args.isEmpty()) return@forEach
            val call = acc.toCall()
            output += call.toItem()
            events += ModelEvent.ToolCallCompleted(call)
        }
        finished = true
        events += ModelEvent.Finished(terminalResponse())
        return events
    }

    private fun flushAssistantIfMissing() {
        if (text.isEmpty() && refusal.isEmpty()) return
        if (output.any { it is ModelItem.Message && it.role == org.koaks.framework.model.Role.ASSISTANT }) return
        output += ModelItem.assistant(
            text = text.toString(),
            ref = textRef,
            nativeId = responseId?.let { ProviderScopedId(ProviderId.OpenAIResponses, it) },
            refusal = refusal.toString().ifBlank { null },
            annotations = annotations.toList(),
        )
    }

    private fun terminalResponse(): ModelResponse {
        val checkpoint = responseId?.let {
            codec.encode(
                responseId = it,
                mode = mode,
                basis = TranscriptBasis.of(basisItems + output),
                scope = if (persistCheckpoint) CheckpointScope.CrossTurn else CheckpointScope.InRun,
            )
        }
        return when {
            incomplete != null -> ModelResponse.Incomplete(
                id = responseId,
                reason = incomplete!!,
                output = output.toList(),
                usage = usage,
                checkpoint = checkpoint,
            )
            else -> ModelResponse.Completed(
                id = responseId,
                output = output.toList(),
                usage = usage,
                checkpoint = checkpoint,
            )
        }
    }

    private fun finishFailed(): List<ModelEvent> {
        if (finished) return emptyList()
        finished = true
        return listOf(ModelEvent.Finished(ModelResponse.Failed(error = failed!!, id = responseId, usage = usage)))
    }

    private fun ingestTerminal(obj: JsonObject) {
        val response = obj.obj("response") ?: obj
        readResponseId(response)
        response.obj("usage")?.let { usage = mapUsage(it) }
        (response["output"] as? JsonArray)?.forEach { el ->
            val item = el as? JsonObject ?: return@forEach
            mapOutputItem(item)?.let { mapped ->
                if (seenRefs.add(mapped.ref.value)) output += mapped
            }
        }
        if (response.str("status") == "incomplete") {
            incomplete = mapIncomplete(response)
        }
    }

    private fun mapOutputItem(item: JsonObject): ModelItem? {
        val type = item.str("type") ?: return null
        val native = item.str("id")?.let { ProviderScopedId(ProviderId.OpenAIResponses, it) }
        return when (type) {
            ResponsesItemTypes.MESSAGE -> {
                val role = when (item.str("role")) {
                    "user" -> org.koaks.framework.model.Role.USER
                    "system" -> org.koaks.framework.model.Role.SYSTEM
                    else -> org.koaks.framework.model.Role.ASSISTANT
                }
                val contentText = extractOutputText(item)
                val anns = extractAnnotations(item)
                val refText = item.str("refusal")
                ModelItem.Message(
                    ref = ItemRef.generate("msg"),
                    nativeId = native,
                    role = role,
                    content = if (contentText.isEmpty()) emptyList()
                    else listOf(org.koaks.framework.model.ContentPart.Text(contentText)),
                    refusal = refText,
                    annotations = anns,
                )
            }
            ResponsesItemTypes.FUNCTION_CALL, ResponsesItemTypes.CUSTOM_TOOL_CALL -> {
                val ref = ItemRef.generate("call")
                ModelItem.ToolCall(
                    ref = ref,
                    nativeId = item.str("call_id")?.let { ProviderScopedId(ProviderId.OpenAIResponses, it) } ?: native,
                    name = item.str("name").orEmpty(),
                    arguments = item.str("arguments") ?: "{}",
                )
            }
            ResponsesItemTypes.FUNCTION_CALL_OUTPUT, ResponsesItemTypes.CUSTOM_TOOL_CALL_OUTPUT,
            ResponsesItemTypes.COMPUTER_CALL_OUTPUT, ResponsesItemTypes.LOCAL_SHELL_CALL_OUTPUT -> {
                val callId = item.str("call_id").orEmpty()
                ModelItem.ToolResult(
                    nativeId = native,
                    callRef = ItemRef(callId.ifBlank { ItemRef.generate("call").value }),
                    output = item.str("output").orEmpty(),
                    isError = item["is_error"]?.jsonPrimitive?.contentOrNull == "true",
                )
            }
            ResponsesItemTypes.REASONING -> {
                val summary = extractReasoningSummary(item)
                if (item["encrypted_content"] != null) {
                    providerItem(item, type, summary.ifBlank { "[reasoning]" }, ReplayPolicy.Required, native)
                } else {
                    ModelItem.ReasoningSummary(
                        nativeId = native,
                        text = summary,
                    )
                }
            }
            ResponsesItemTypes.COMPACTION ->
                providerItem(item, type, item.str("encrypted_content")?.let { "[compaction]" } ?: "[compaction]", ReplayPolicy.Required, native)
            else -> providerItem(
                item,
                type,
                displayFor(item, type),
                if (type in ResponsesItemTypes.ALL) ReplayPolicy.Preferred else ReplayPolicy.Required,
                native,
            )
        }
    }

    private fun providerItem(
        item: JsonObject,
        type: String,
        display: String,
        replay: ReplayPolicy,
        native: ProviderScopedId?,
    ) = ModelItem.ProviderItem(
        nativeId = native,
        providerId = ProviderId.OpenAIResponses,
        kind = type,
        displayText = display,
        replay = replay,
        payload = item.toString().encodeUtf8(),
    )

    private fun extractOutputText(item: JsonObject): String {
        val content = item["content"] as? JsonArray ?: return item.str("text").orEmpty()
        return content.mapNotNull { el ->
            val part = el as? JsonObject ?: return@mapNotNull null
            when (part.str("type")) {
                "output_text", "input_text", "text" -> part.str("text")
                else -> null
            }
        }.joinToString("")
    }

    private fun extractAnnotations(item: JsonObject): List<Annotation> {
        val content = item["content"] as? JsonArray ?: return emptyList()
        return content.flatMap { el ->
            val part = el as? JsonObject ?: return@flatMap emptyList()
            val anns = part["annotations"] as? JsonArray ?: return@flatMap emptyList()
            anns.mapNotNull { a -> (a as? JsonObject)?.let { mapAnnotation(it) } }
        }
    }

    private fun extractReasoningSummary(item: JsonObject): String {
        val summary = item["summary"] as? JsonArray ?: return item.str("content").orEmpty()
        return summary.mapNotNull { el ->
            val part = el as? JsonObject ?: return@mapNotNull null
            part.str("text")
        }.joinToString("")
    }

    private fun mapAnnotation(obj: JsonObject): Annotation = when (obj.str("type")) {
        "url_citation" -> Annotation.UrlCitation(
            url = obj.str("url").orEmpty(),
            title = obj.str("title"),
            startIndex = obj.int("start_index"),
            endIndex = obj.int("end_index"),
        )
        "file_citation" -> Annotation.FileCitation(
            fileId = obj.str("file_id").orEmpty(),
            filename = obj.str("filename"),
            startIndex = obj.int("start_index"),
            endIndex = obj.int("end_index"),
        )
        else -> Annotation.Generic(obj.str("type") ?: "unknown", obj.toString())
    }

    private fun mapUsage(obj: JsonObject): Usage {
        val input = obj.int("input_tokens") ?: 0
        val outputTok = obj.int("output_tokens") ?: 0
        val total = obj.int("total_tokens") ?: (input + outputTok)
        val cached = obj.obj("input_tokens_details")?.int("cached_tokens") ?: 0
        val reasoning = obj.obj("output_tokens_details")?.int("reasoning_tokens") ?: 0
        return Usage(
            promptTokens = input,
            completionTokens = outputTok,
            totalTokens = total,
            cachedInputTokens = cached,
            reasoningOutputTokens = reasoning,
        )
    }

    private fun mapIncomplete(obj: JsonObject): IncompleteReason {
        val reason = obj.obj("incomplete_details")?.str("reason")
            ?: obj.str("reason")
            ?: "unknown"
        return when (reason) {
            "max_output_tokens" -> IncompleteReason.MaxOutputTokens
            "content_filter" -> IncompleteReason.ContentFilter
            "cancelled" -> IncompleteReason.Cancelled
            else -> IncompleteReason.Other(reason)
        }
    }

    private fun readResponseId(obj: JsonObject) {
        responseId = obj.str("id")
            ?: obj.obj("response")?.str("id")
            ?: responseId
    }

    private fun decodeHttpError(frame: WireFrame.HttpError): AgentError.ModelError {
        val parsed = runCatching { JsonUtil.json.parseToJsonElement(frame.body).jsonObject }.getOrNull()
        val err = parsed?.obj("error") ?: parsed
        return AgentError.ModelError(
            message = err?.str("message") ?: "HTTP ${frame.status}: ${frame.body}",
            retriable = frame.status == 429 || frame.status >= 500,
        )
    }

    private fun decodeModelError(obj: JsonObject): AgentError.ModelError {
        val err = obj.obj("error") ?: obj.obj("response")?.obj("error") ?: obj
        return AgentError.ModelError(
            message = err.str("message") ?: "openai responses error ${err.str("code")}",
            retriable = false,
        )
    }

    private fun displayFor(item: JsonObject, type: String): String =
        item.str("name") ?: extractOutputText(item).ifBlank { "[$type]" }

    private fun ToolAcc.toCall(): ToolCall = ToolCall(
        id = ref.value,
        name = name.toString(),
        arguments = args.toString().ifBlank { "{}" },
        nativeId = (callId ?: nativeItemId)?.let { ProviderScopedId(ProviderId.OpenAIResponses, it) },
    )
}

private fun JsonObject.str(key: String): String? =
    this[key]?.let { el -> (el as? JsonPrimitive)?.contentOrNull }

private fun JsonObject.int(key: String): Int? =
    this[key]?.let { el -> (el as? JsonPrimitive)?.intOrNull }

private fun JsonObject.obj(key: String): JsonObject? =
    this[key] as? JsonObject
