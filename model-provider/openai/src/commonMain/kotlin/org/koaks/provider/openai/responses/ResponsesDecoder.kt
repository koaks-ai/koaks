package org.koaks.provider.openai.responses

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okio.ByteString.Companion.encodeUtf8
import org.koaks.framework.model.AgentError
import org.koaks.framework.model.Annotation
import org.koaks.framework.model.CheckpointScope
import org.koaks.framework.model.ContentPart
import org.koaks.framework.model.EventDetail
import org.koaks.framework.model.IncompleteReason
import org.koaks.framework.model.ItemRef
import org.koaks.framework.model.ModelEvent
import org.koaks.framework.model.ModelItem
import org.koaks.framework.model.ModelResponse
import org.koaks.framework.model.ProviderId
import org.koaks.framework.model.ProtocolId
import org.koaks.framework.model.ProviderScopedId
import org.koaks.framework.model.ReplayPolicy
import org.koaks.framework.model.Role
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
    private val eventDetail: EventDetail = EventDetail.SEMANTIC,
) : WireDecoder {

    private class ToolAcc(
        var callId: String? = null,
        var nativeItemId: String? = null,
        val name: StringBuilder = StringBuilder(),
        val args: StringBuilder = StringBuilder(),
        val ref: ItemRef = ItemRef.generate("call"),
    )

    private class MessageAcc(
        var nativeItemId: String? = null,
        var role: Role = Role.ASSISTANT,
        val text: StringBuilder = StringBuilder(),
        val refusal: StringBuilder = StringBuilder(),
        val annotations: MutableList<Annotation> = mutableListOf(),
        val ref: ItemRef = ItemRef.generate("msg"),
    )

    private class ReasoningAcc(
        var nativeItemId: String? = null,
        val ref: ItemRef = ItemRef.generate("reason"),
    )

    private val tools = LinkedHashMap<Int, ToolAcc>()
    private val messages = LinkedHashMap<Int, MessageAcc>()
    private val reasoning = LinkedHashMap<Int, ReasoningAcc>()
    private val output = mutableListOf<ModelItem>()
    private val seenKeys = HashSet<String>()
    private val emittedCallRefs = HashSet<String>()
    private var usage: Usage = Usage.ZERO
    private var responseId: String? = null
    private var failed: AgentError.ModelError? = null
    private var incomplete: IncompleteReason? = null
    private var finished = false
    private var started = false

    override fun accept(frame: WireFrame): List<ModelEvent> {
        val raw = if (eventDetail == EventDetail.LOSSLESS) listOf(providerEvent(frame)) else emptyList()
        val semantic = when (frame) {
            is WireFrame.HttpError -> {
                failed = decodeHttpError(frame)
                finishFailed()
            }
            is WireFrame.Sse -> acceptEvent(frame.event, frame.data)
            is WireFrame.Body -> acceptCompletedJson(frame.text)
            is WireFrame.Ndjson -> acceptEvent(null, frame.line)
        }
        return raw + semantic
    }

    internal fun acceptEvent(event: String?, data: String): List<ModelEvent> {
        if (data.isBlank() || data == "[DONE]") return emptyList()
        val obj = runCatching { JsonUtil.json.parseToJsonElement(data).jsonObject }.getOrElse { return emptyList() }
        return dispatch(event ?: obj.str("type"), obj)
    }

    private fun acceptCompletedJson(text: String): List<ModelEvent> {
        val obj = runCatching { JsonUtil.json.parseToJsonElement(text).jsonObject }.getOrElse { return emptyList() }
        val event = when (obj.str("status")) {
            "queued" -> "response.queued"
            "in_progress" -> "response.in_progress"
            "completed" -> "response.completed"
            "incomplete" -> "response.incomplete"
            "failed" -> "response.failed"
            "cancelled" -> "response.cancelled"
            else -> obj.str("type")
        }
        val wrapped = wrapResponse(obj)
        val eventStartsResponse = event == "response.created" ||
            event == "response.queued" ||
            event == "response.in_progress"
        val startedEvents = if (started || eventStartsResponse) emptyList() else dispatch("response.created", wrapped)
        return startedEvents + dispatch(event, wrapped)
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
                currentCheckpoint()?.let { events += ModelEvent.CheckpointUpdated(it) }
            }
            "response.output_text.delta" -> {
                val delta = obj.str("delta").orEmpty()
                if (delta.isNotEmpty()) {
                    val acc = messageAccFor(obj.int("output_index"), obj.str("item_id"))
                    acc.text.append(delta)
                    events += ModelEvent.TextDelta(delta, acc.ref)
                }
            }
            "response.refusal.delta" -> obj.str("delta")?.let { delta ->
                val acc = messageAccFor(obj.int("output_index"), obj.str("item_id"))
                acc.refusal.append(delta)
                if (delta.isNotEmpty()) events += ModelEvent.RefusalDelta(delta, acc.ref)
            }
            "response.reasoning_summary_text.delta" -> {
                val delta = obj.str("delta").orEmpty()
                if (delta.isNotEmpty()) {
                    val acc = reasoningAccFor(obj.int("output_index"), obj.str("item_id"))
                    events += ModelEvent.ReasoningDelta(delta, acc.ref, ModelEvent.ReasoningKind.SUMMARY)
                }
            }
            "response.reasoning_text.delta", "response.reasoning.delta" -> {
                val delta = obj.str("delta").orEmpty()
                if (delta.isNotEmpty()) {
                    val acc = reasoningAccFor(obj.int("output_index"), obj.str("item_id"))
                    events += ModelEvent.ReasoningDelta(delta, acc.ref, ModelEvent.ReasoningKind.RAW)
                }
            }
            "response.function_call_arguments.delta" -> {
                val index = obj.int("output_index") ?: 0
                val acc = accFor(index = index, itemId = obj.str("item_id"))
                val fragment = obj.str("delta").orEmpty()
                acc.args.append(fragment)
                events += ModelEvent.ToolCallDelta(
                    id = acc.ref.value,
                    index = index,
                    argumentsDelta = fragment,
                    itemRef = acc.ref,
                )
            }
            "response.output_item.added" -> {
                val item = obj.obj("item") ?: return events
                when (item.str("type")) {
                    ResponsesItemTypes.FUNCTION_CALL, ResponsesItemTypes.CUSTOM_TOOL_CALL -> {
                        val acc = accFor(
                            index = obj.int("output_index"),
                            callId = item.str("call_id"),
                            itemId = item.str("id"),
                        )
                        if (acc.name.isEmpty()) item.str("name")?.let { acc.name.append(it) }
                        if (acc.args.isEmpty()) {
                            item.str("arguments")?.takeIf { it.isNotEmpty() }?.let { acc.args.append(it) }
                        }
                    }
                    ResponsesItemTypes.MESSAGE -> messageAccFor(
                        index = obj.int("output_index"),
                        itemId = item.str("id"),
                        role = item.role(),
                    )
                    ResponsesItemTypes.REASONING -> reasoningAccFor(
                        index = obj.int("output_index"),
                        itemId = item.str("id"),
                    )
                    else -> Unit
                }
            }
            "response.output_item.done" -> {
                val item = obj.obj("item") ?: return events
                mapOutputItem(item, obj.int("output_index"))?.let { mapped ->
                    events += emitMapped(mapped)
                }
            }
            "response.output_text.annotation.added" -> {
                obj.obj("annotation")?.let {
                    val acc = messageAccFor(obj.int("output_index"), obj.str("item_id"))
                    val annotation = mapAnnotation(it)
                    acc.annotations += annotation
                    events += ModelEvent.AnnotationAdded(annotation, acc.ref)
                }
            }
            "response.completed" -> {
                ingestTerminal(obj)
            }
            "response.incomplete" -> {
                ingestTerminal(obj)
            }
            "response.cancelled" -> {
                ingestTerminal(obj)
                incomplete = IncompleteReason.Cancelled
            }
            "response.failed", "error" -> {
                failed = decodeModelError(obj)
                return events + finishFailed()
            }
            else -> {
                if (event != null && eventDetail != EventDetail.LOSSLESS) {
                    events += ModelEvent.ProviderEvent(
                        providerId = ProviderId.OpenAIResponses,
                        type = event,
                        payload = obj.toString(),
                        protocolId = ProtocolId.OpenAIResponses,
                    )
                }
            }
        }
        return events
    }

    override fun finish(): List<ModelEvent> {
        if (finished) return emptyList()
        if (failed != null) return finishFailed()
        val events = mutableListOf<ModelEvent>()
        messages.entries.sortedBy { it.key }.forEach { (_, acc) ->
            if (acc.text.isEmpty() && acc.refusal.isEmpty()) return@forEach
            events += emitMapped(acc.toItem())
        }
        tools.entries.sortedBy { it.key }.forEach { (_, acc) ->
            if (acc.name.isEmpty() && acc.args.isEmpty()) return@forEach
            events += emitMapped(acc.toItem())
        }
        finished = true
        events += ModelEvent.Finished(terminalResponse())
        return events
    }

    private fun terminalResponse(): ModelResponse {
        val checkpoint = currentCheckpoint()
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
        (response["output"] as? JsonArray)?.forEachIndexed { index, el ->
            val item = el as? JsonObject ?: return@forEachIndexed
            mapOutputItem(item, index)?.let { mapped -> remember(mapped) }
        }
        if (response.str("status") == "incomplete") {
            incomplete = mapIncomplete(response)
        }
    }

    private fun accFor(
        index: Int? = null,
        callId: String? = null,
        itemId: String? = null,
    ): ToolAcc {
        tools.values.firstOrNull { acc ->
            (callId != null && acc.callId == callId) || (itemId != null && acc.nativeItemId == itemId)
        }?.let { acc ->
            callId?.let { acc.callId = it }
            itemId?.let { acc.nativeItemId = it }
            return acc
        }
        val key = index ?: (tools.keys.maxOrNull()?.plus(1) ?: 0)
        return tools.getOrPut(key) { ToolAcc() }.also { acc ->
            callId?.let { acc.callId = it }
            itemId?.let { acc.nativeItemId = it }
        }
    }

    private fun messageAccFor(
        index: Int? = null,
        itemId: String? = null,
        role: Role? = null,
    ): MessageAcc {
        messages.values.firstOrNull { itemId != null && it.nativeItemId == itemId }?.let { acc ->
            itemId?.let { acc.nativeItemId = it }
            role?.let { acc.role = it }
            return acc
        }
        val key = index ?: (messages.keys.maxOrNull()?.plus(1) ?: 0)
        return messages.getOrPut(key) { MessageAcc() }.also { acc ->
            itemId?.let { acc.nativeItemId = it }
            role?.let { acc.role = it }
        }
    }

    private fun reasoningAccFor(index: Int? = null, itemId: String? = null): ReasoningAcc {
        reasoning.values.firstOrNull { itemId != null && it.nativeItemId == itemId }?.let { acc ->
            itemId?.let { acc.nativeItemId = it }
            return acc
        }
        val key = index ?: (reasoning.keys.maxOrNull()?.plus(1) ?: 0)
        return reasoning.getOrPut(key) { ReasoningAcc() }.also { acc ->
            itemId?.let { acc.nativeItemId = it }
        }
    }

    private fun seenKey(item: ModelItem): String {
        val native = item.nativeId?.raw
        return when (item) {
            is ModelItem.ToolCall -> "call:${native ?: item.ref.value}"
            is ModelItem.ToolResult -> "result:${item.callRef.value}:${native ?: item.ref.value}"
            is ModelItem.Message -> "msg:${item.role}:${native ?: item.ref.value}"
            is ModelItem.ReasoningSummary -> "reason:${native ?: item.ref.value}"
            is ModelItem.ProviderItem -> "prov:${item.kind}:${native ?: item.ref.value}"
        }
    }

    private fun remember(item: ModelItem): Boolean {
        if (!seenKeys.add(seenKey(item))) return false
        output += item
        return true
    }

    private fun emitMapped(mapped: ModelItem): List<ModelEvent> {
        val events = mutableListOf<ModelEvent>()
        if (remember(mapped)) {
            events += ModelEvent.ItemAdded(mapped)
            currentCheckpoint()?.let { events += ModelEvent.CheckpointUpdated(it) }
        }
        if (mapped is ModelItem.ToolCall && emittedCallRefs.add(mapped.ref.value)) {
            events += ModelEvent.ToolCallCompleted(mapped.toDispatchCall())
        }
        return events
    }

    private fun mapOutputItem(item: JsonObject, outputIndex: Int? = null): ModelItem? {
        val type = item.str("type") ?: return null
        val native = item.str("id")?.let { ProviderScopedId(ProviderId.OpenAIResponses, it) }
        return when (type) {
            ResponsesItemTypes.MESSAGE -> {
                val acc = messageAccFor(outputIndex, item.str("id"), item.role())
                val contentText = extractOutputText(item)
                if (contentText.isNotEmpty()) {
                    acc.text.clear()
                    acc.text.append(contentText)
                }
                val anns = extractAnnotations(item)
                if (anns.isNotEmpty()) {
                    acc.annotations.clear()
                    acc.annotations += anns
                }
                extractRefusal(item)?.let { refusalText ->
                    acc.refusal.clear()
                    acc.refusal.append(refusalText)
                }
                acc.toItem()
            }
            ResponsesItemTypes.FUNCTION_CALL, ResponsesItemTypes.CUSTOM_TOOL_CALL -> {
                val acc = accFor(
                    index = outputIndex,
                    callId = item.str("call_id"),
                    itemId = item.str("id"),
                )
                if (acc.name.isEmpty()) item.str("name")?.let { acc.name.append(it) }
                if (acc.args.isEmpty()) {
                    item.str("arguments")?.takeIf { it.isNotEmpty() }?.let { acc.args.append(it) }
                }
                acc.toItem()
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
                val acc = reasoningAccFor(outputIndex, item.str("id"))
                val summary = extractReasoningSummary(item)
                if (item["encrypted_content"] != null) {
                    providerItem(
                        item,
                        type,
                        summary.ifBlank { "[reasoning]" },
                        ReplayPolicy.Required,
                        native,
                        acc.ref,
                    )
                } else {
                    ModelItem.ReasoningSummary(
                        ref = acc.ref,
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
        ref: ItemRef = ItemRef.generate("ext"),
    ) = ModelItem.ProviderItem(
        ref = ref,
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

    private fun extractRefusal(item: JsonObject): String? {
        val content = item["content"] as? JsonArray ?: return item.str("refusal")
        return content.mapNotNull { el ->
            val part = el as? JsonObject ?: return@mapNotNull null
            part.takeIf { it.str("type") == "refusal" }?.str("refusal")
        }.joinToString("").ifBlank { null }
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

    private fun currentCheckpoint() = responseId?.let {
        codec.encode(
            responseId = it,
            mode = mode,
            basis = TranscriptBasis.of(basisItems + output),
            scope = if (persistCheckpoint) CheckpointScope.CrossTurn else CheckpointScope.InRun,
        )
    }

    private fun decodeHttpError(frame: WireFrame.HttpError): AgentError.ModelError {
        val parsed = runCatching { JsonUtil.json.parseToJsonElement(frame.body).jsonObject }.getOrNull()
        val err = parsed?.obj("error") ?: parsed
        return AgentError.ModelError(
            message = err?.str("message") ?: "HTTP ${frame.status}: ${frame.body}",
            retriable = frame.status == 429 || frame.status >= 500,
        )
    }

    private fun providerEvent(frame: WireFrame): ModelEvent.ProviderEvent {
        val payload = when (frame) {
            is WireFrame.Sse -> frame.data
            is WireFrame.Body -> frame.text
            is WireFrame.Ndjson -> frame.line
            is WireFrame.HttpError -> frame.body
        }
        val parsed = payload.takeUnless { it.isBlank() || it == "[DONE]" }
            ?.let { runCatching { JsonUtil.json.parseToJsonElement(it).jsonObject }.getOrNull() }
        val type = when (frame) {
            is WireFrame.Sse -> frame.event ?: parsed?.str("type") ?: if (payload == "[DONE]") "done" else "response.unknown"
            is WireFrame.Body -> responseEventType(parsed)
            is WireFrame.Ndjson -> parsed?.str("type") ?: "response.unknown"
            is WireFrame.HttpError -> "http.error"
        }
        return ModelEvent.ProviderEvent(
            providerId = ProviderId.OpenAIResponses,
            protocolId = ProtocolId.OpenAIResponses,
            type = type,
            payload = payload,
            source = when (frame) {
                is WireFrame.Sse -> ModelEvent.ProviderEventSource.SSE
                is WireFrame.Body -> ModelEvent.ProviderEventSource.BODY
                is WireFrame.Ndjson -> ModelEvent.ProviderEventSource.NDJSON
                is WireFrame.HttpError -> ModelEvent.ProviderEventSource.HTTP_ERROR
            },
            eventId = (frame as? WireFrame.Sse)?.id,
            sequenceNumber = parsed?.long("sequence_number"),
            statusCode = (frame as? WireFrame.HttpError)?.status,
            contentType = when (frame) {
                is WireFrame.Body -> frame.contentType
                is WireFrame.HttpError -> frame.contentType
                else -> null
            },
        )
    }

    private fun responseEventType(obj: JsonObject?): String = when (obj?.str("status")) {
        "queued" -> "response.queued"
        "in_progress" -> "response.in_progress"
        "completed" -> "response.completed"
        "incomplete" -> "response.incomplete"
        "failed" -> "response.failed"
        "cancelled" -> "response.cancelled"
        else -> obj?.str("type") ?: "response.unknown"
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
        nativeId = callId?.let { ProviderScopedId(ProviderId.OpenAIResponses, it) }
            ?: nativeItemId?.let { ProviderScopedId(ProviderId.OpenAIResponses, it) },
        nativeItemId = nativeItemId?.let { ProviderScopedId(ProviderId.OpenAIResponses, it) },
    )

    private fun ToolAcc.toItem(): ModelItem.ToolCall = toCall().toItem()

    private fun MessageAcc.toItem(): ModelItem.Message = ModelItem.Message(
        ref = ref,
        nativeId = nativeItemId?.let { ProviderScopedId(ProviderId.OpenAIResponses, it) },
        role = role,
        content = if (text.isEmpty()) emptyList()
        else listOf(ContentPart.Text(text.toString())),
        refusal = refusal.toString().ifBlank { null },
        annotations = annotations.toList(),
    )
}

private fun JsonObject.str(key: String): String? =
    this[key]?.let { el -> (el as? JsonPrimitive)?.contentOrNull }

private fun JsonObject.int(key: String): Int? =
    this[key]?.let { el -> (el as? JsonPrimitive)?.intOrNull }

private fun JsonObject.long(key: String): Long? =
    this[key]?.let { el -> (el as? JsonPrimitive)?.longOrNull }

private fun JsonObject.obj(key: String): JsonObject? =
    this[key] as? JsonObject

private fun JsonObject.role(): Role = when (str("role")) {
    "user" -> Role.USER
    "system", "developer" -> Role.SYSTEM
    else -> Role.ASSISTANT
}
