package org.koaks.provider.chatcompletions

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import org.koaks.framework.model.AgentError
import org.koaks.framework.model.EventDetail
import org.koaks.framework.model.ItemRef
import org.koaks.framework.model.ModelEvent
import org.koaks.framework.model.ModelItem
import org.koaks.framework.model.ModelResponse
import org.koaks.framework.model.ProviderId
import org.koaks.framework.model.ProtocolId
import org.koaks.framework.model.ProviderScopedId
import org.koaks.framework.model.ToolCall
import org.koaks.framework.model.Usage
import org.koaks.framework.provider.WireDecoder
import org.koaks.framework.transport.WireFrame
import org.koaks.framework.utils.json.JsonUtil

class ChatCompletionsDecoder(
    private val providerId: ProviderId,
    private val eventDetail: EventDetail = EventDetail.SEMANTIC,
) : WireDecoder {

    private class ToolAcc(
        var id: String? = null,
        val name: StringBuilder = StringBuilder(),
        val args: StringBuilder = StringBuilder(),
        val ref: ItemRef = ItemRef.generate("call"),
    )

    private val toolCalls = LinkedHashMap<Int, ToolAcc>()
    private val text = StringBuilder()
    private val refusal = StringBuilder()
    private val textRef = ItemRef.generate("msg")
    private val reasoningRef = ItemRef.generate("reason")
    private val output = mutableListOf<ModelItem>()
    private var usage: Usage = Usage.ZERO
    private var responseId: String? = null
    private var failed: AgentError.ModelError? = null
    private var finished = false

    private var started = false

    override fun accept(frame: WireFrame): List<ModelEvent> {
        val raw = if (eventDetail == EventDetail.LOSSLESS) listOf(providerEvent(frame)) else emptyList()
        val json = when (frame) {
            is WireFrame.Sse -> frame.data
            is WireFrame.Ndjson -> frame.line
            is WireFrame.Body -> frame.text
            is WireFrame.HttpError -> {
                failed = AgentError.ModelError(
                    message = "HTTP ${frame.status}: ${frame.body}",
                    retriable = frame.status == 429 || frame.status >= 500,
                )
                return raw + finishFailed()
            }
        }
        if (json.isBlank() || json == "[DONE]") return raw
        val chunk = runCatching {
            JsonUtil.fromJson(json, ChatCompletionsResponse.serializer())
        }.getOrElse { return raw }
        return raw + acceptChunk(chunk)
    }

    fun acceptChunk(chunk: ChatCompletionsResponse): List<ModelEvent> {
        val events = mutableListOf<ModelEvent>()
        chunk.id?.let { responseId = it }
        if (!started && responseId != null) {
            started = true
            events += ModelEvent.Started(responseId)
        }

        chunk.error?.let { err ->
            failed = AgentError.ModelError(
                message = err.message ?: "${providerId.value} error ${err.code}",
                retriable = false,
            )
            return events + finishFailed()
        }

        chunk.usage?.let {
            usage = Usage(
                promptTokens = it.promptTokens ?: 0,
                completionTokens = it.completionTokens ?: 0,
                totalTokens = it.totalTokens ?: 0,
                cachedInputTokens = it.promptDetails?.cachedTokens ?: 0,
                reasoningOutputTokens = it.completionDetails?.reasoningTokens ?: 0,
            )
        }

        val payload = chunk.choices?.firstOrNull()?.payload ?: return events
        payload.reasoningContent?.let {
            if (it.isNotEmpty()) {
                events += ModelEvent.ReasoningDelta(it, reasoningRef, ModelEvent.ReasoningKind.RAW)
            }
        }
        payload.content?.let {
            if (it.isNotEmpty()) {
                text.append(it)
                events += ModelEvent.TextDelta(it, textRef)
            }
        }
        payload.refusal?.let {
            if (it.isNotEmpty()) {
                refusal.append(it)
                events += ModelEvent.RefusalDelta(it, textRef)
            }
        }
        payload.toolCalls?.forEach { tc ->
            val acc = toolCalls.getOrPut(tc.index) { ToolAcc() }
            tc.id?.let { if (acc.id == null) acc.id = it }
            tc.function?.name?.let { acc.name.append(it) }
            tc.function?.arguments?.let { acc.args.append(it) }
            events += ModelEvent.ToolCallDelta(
                id = tc.id ?: acc.id ?: acc.ref.value,
                index = tc.index,
                nameDelta = tc.function?.name,
                argumentsDelta = tc.function?.arguments,
                itemRef = acc.ref,
            )
        }
        return events
    }

    override fun finish(): List<ModelEvent> {
        if (finished) return emptyList()
        if (failed != null) return finishFailed()
        val events = mutableListOf<ModelEvent>()
        if (text.isNotEmpty()) {
            output += ModelItem.assistant(
                text = text.toString(),
                ref = textRef,
                refusal = refusal.toString().ifBlank { null },
            )
        } else if (refusal.isNotEmpty()) {
            output += ModelItem.assistant("", ref = textRef, refusal = refusal.toString())
        }
        toolCalls.entries.sortedBy { it.key }.forEach { (_, acc) ->
            if (acc.name.isEmpty() && acc.args.isEmpty() && acc.id == null) return@forEach
            val call = ToolCall(
                id = acc.ref.value,
                name = acc.name.toString(),
                arguments = acc.args.toString().ifBlank { "{}" },
                nativeId = acc.id?.let { ProviderScopedId(providerId, it) },
            )
            output += call.toItem()
            events += ModelEvent.ToolCallCompleted(call)
        }
        finished = true
        events += ModelEvent.Finished(
            ModelResponse.Completed(
                id = responseId,
                output = output.toList(),
                usage = usage,
            ),
        )
        return events
    }

    private fun finishFailed(): List<ModelEvent> {
        if (finished) return emptyList()
        finished = true
        return listOf(ModelEvent.Finished(ModelResponse.Failed(error = failed!!, id = responseId, usage = usage)))
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
        return ModelEvent.ProviderEvent(
            providerId = providerId,
            protocolId = ProtocolId.ChatCompletions,
            type = when (frame) {
                is WireFrame.Sse -> frame.event ?: parsed.str("object") ?: if (payload == "[DONE]") "done" else "chat.completion.chunk"
                is WireFrame.Body -> parsed.str("object") ?: "chat.completion"
                is WireFrame.Ndjson -> parsed.str("object") ?: "chat.completion.chunk"
                is WireFrame.HttpError -> "http.error"
            },
            payload = payload,
            source = when (frame) {
                is WireFrame.Sse -> ModelEvent.ProviderEventSource.SSE
                is WireFrame.Body -> ModelEvent.ProviderEventSource.BODY
                is WireFrame.Ndjson -> ModelEvent.ProviderEventSource.NDJSON
                is WireFrame.HttpError -> ModelEvent.ProviderEventSource.HTTP_ERROR
            },
            eventId = (frame as? WireFrame.Sse)?.id,
            sequenceNumber = parsed.long("sequence_number"),
            statusCode = (frame as? WireFrame.HttpError)?.status,
            contentType = when (frame) {
                is WireFrame.Body -> frame.contentType
                is WireFrame.HttpError -> frame.contentType
                else -> null
            },
        )
    }
}

private fun JsonObject?.str(key: String): String? =
    this?.get(key)?.let { (it as? JsonPrimitive)?.contentOrNull }

private fun JsonObject?.long(key: String): Long? =
    this?.get(key)?.let { (it as? JsonPrimitive)?.longOrNull }
