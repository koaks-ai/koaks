package org.koaks.provider.anthropic

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okio.ByteString.Companion.encodeUtf8
import org.koaks.framework.model.AgentError
import org.koaks.framework.model.EventDetail
import org.koaks.framework.model.ItemRef
import org.koaks.framework.model.ModelEvent
import org.koaks.framework.model.ModelItem
import org.koaks.framework.model.ModelResponse
import org.koaks.framework.model.ProtocolId
import org.koaks.framework.model.ProviderId
import org.koaks.framework.model.ProviderScopedId
import org.koaks.framework.model.ReplayPolicy
import org.koaks.framework.model.ToolCall
import org.koaks.framework.model.Usage
import org.koaks.framework.provider.WireDecoder
import org.koaks.framework.transport.WireFrame
import org.koaks.framework.utils.json.JsonUtil

class AnthropicWireDecoder(
    private val eventDetail: EventDetail = EventDetail.SEMANTIC,
) : WireDecoder {

    private class ToolAcc(val id: String, val name: String, val args: StringBuilder = StringBuilder(), val ref: ItemRef = ItemRef.generate("call"))
    private class ThinkingAcc(
        var text: StringBuilder = StringBuilder(),
        val signature: StringBuilder = StringBuilder(),
        var redacted: String? = null,
        val kind: String,
        val ref: ItemRef = ItemRef.generate("think"),
    )

    private val toolCalls = LinkedHashMap<Int, ToolAcc>()
    private val thinking = LinkedHashMap<Int, ThinkingAcc>()
    private val output = mutableListOf<ModelItem>()
    private val text = StringBuilder()
    private val textRef = ItemRef.generate("msg")
    private var promptTokens = 0
    private var completionTokens = 0
    private var cachedInput = 0
    private var failed: AgentError.ModelError? = null
    private var finished = false
    private var started = false

    override fun accept(frame: WireFrame): List<ModelEvent> {
        val raw = if (eventDetail == EventDetail.LOSSLESS) listOf(providerEvent(frame)) else emptyList()
        val json = when (frame) {
            is WireFrame.Sse -> frame.data
            is WireFrame.Body -> frame.text
            is WireFrame.Ndjson -> frame.line
            is WireFrame.HttpError -> {
                failed = AgentError.ModelError("HTTP ${frame.status}: ${frame.body}", retriable = frame.status >= 500)
                return raw + finishFailed()
            }
        }
        if (json.isBlank()) return raw
        val chunk = runCatching {
            JsonUtil.fromJson(json, AnthropicChatResponse.serializer())
        }.getOrElse { return raw }
        return raw + acceptChunk(chunk)
    }

    fun acceptChunk(chunk: AnthropicChatResponse): List<ModelEvent> {
        val events = mutableListOf<ModelEvent>()
        if (!started) {
            started = true
            events += ModelEvent.Started(null)
        }
        when (chunk.type) {
            "error" -> {
                failed = AgentError.ModelError(
                    message = chunk.error?.message ?: "anthropic error ${chunk.error?.type}",
                    retriable = false,
                )
                return events + finishFailed()
            }
            "message_start" -> {
                chunk.message?.id?.let { responseId ->
                    if (events.firstOrNull() is ModelEvent.Started) {
                        events[0] = ModelEvent.Started(responseId)
                    }
                }
                chunk.message?.usage?.inputTokens?.let { promptTokens = it }
            }
            "content_block_start" -> {
                val block = chunk.contentBlock
                val index = chunk.index ?: 0
                when (block?.type) {
                    "tool_use" -> toolCalls[index] = ToolAcc(id = block.id ?: "", name = block.name ?: "")
                    "thinking" -> thinking[index] = ThinkingAcc(kind = "thinking")
                    "redacted_thinking" -> thinking[index] = ThinkingAcc(
                        kind = "redacted_thinking",
                        redacted = block.data,
                    )
                }
            }
            "content_block_delta" -> {
                val delta = chunk.delta
                when (delta?.type) {
                    "text_delta" -> delta.text?.let {
                        if (it.isNotEmpty()) {
                            text.append(it)
                            events += ModelEvent.TextDelta(it, textRef)
                        }
                    }
                    "thinking_delta" -> delta.thinking?.let {
                        val index = chunk.index ?: 0
                        val acc = thinking.getOrPut(index) { ThinkingAcc(kind = "thinking") }
                        acc.text.append(it)
                        if (it.isNotEmpty()) {
                            events += ModelEvent.ReasoningDelta(
                                text = it,
                                itemRef = acc.ref,
                                kind = ModelEvent.ReasoningKind.RAW,
                            )
                        }
                    }
                    "signature_delta" -> delta.signature?.let { sig ->
                        chunk.index?.let { idx -> thinking[idx]?.signature?.append(sig) }
                    }
                    "input_json_delta" -> {
                        val index = chunk.index ?: return events
                        val acc = toolCalls[index] ?: return events
                        val fragment = delta.partialJson ?: return events
                        acc.args.append(fragment)
                        events += ModelEvent.ToolCallDelta(
                            id = acc.id,
                            index = index,
                            argumentsDelta = fragment,
                            itemRef = acc.ref,
                        )
                    }
                }
            }
            "content_block_stop" -> {
                val index = chunk.index ?: return events
                thinking.remove(index)?.let { acc ->
                    val block: AnthropicContentBlock = if (acc.kind == "redacted_thinking") {
                        AnthropicContentBlock.RedactedThinking(acc.redacted ?: "")
                    } else {
                        AnthropicContentBlock.Thinking(acc.text.toString(), acc.signature.toString().ifBlank { null })
                    }
                    val item = ModelItem.ProviderItem(
                        ref = acc.ref,
                        providerId = ProviderId.Anthropic,
                        kind = acc.kind,
                        displayText = acc.text.toString().ifBlank { "[redacted thinking]" },
                        replay = ReplayPolicy.Required,
                        payload = JsonUtil.toJson(block, AnthropicContentBlock.serializer()).encodeUtf8(),
                    )
                    output += item
                    events += ModelEvent.ItemAdded(item)
                }
            }
            "message_delta" -> chunk.usage?.outputTokens?.let { completionTokens = it }
        }
        return events
    }

    override fun finish(): List<ModelEvent> {
        if (finished) return emptyList()
        if (failed != null) return finishFailed()
        val events = mutableListOf<ModelEvent>()
        if (text.isNotEmpty()) output += ModelItem.assistant(text.toString(), ref = textRef)
        toolCalls.entries.sortedBy { it.key }.forEach { (_, acc) ->
            val call = ToolCall(
                id = acc.ref.value,
                name = acc.name,
                arguments = acc.args.toString().ifBlank { "{}" },
                nativeId = ProviderScopedId(ProviderId.Anthropic, acc.id),
            )
            output += call.toItem()
            events += ModelEvent.ToolCallCompleted(call)
        }
        finished = true
        events += ModelEvent.Finished(
            ModelResponse.Completed(
                output = output.toList(),
                usage = Usage(
                    promptTokens = promptTokens,
                    completionTokens = completionTokens,
                    totalTokens = promptTokens + completionTokens,
                    cachedInputTokens = cachedInput,
                ),
            ),
        )
        return events
    }

    private fun finishFailed(): List<ModelEvent> {
        if (finished) return emptyList()
        finished = true
        return listOf(
            ModelEvent.Finished(
                ModelResponse.Failed(
                    error = failed!!,
                    usage = Usage(promptTokens = promptTokens, completionTokens = completionTokens, totalTokens = promptTokens + completionTokens),
                ),
            ),
        )
    }

    private fun providerEvent(frame: WireFrame): ModelEvent.ProviderEvent {
        val payload = when (frame) {
            is WireFrame.Sse -> frame.data
            is WireFrame.Body -> frame.text
            is WireFrame.Ndjson -> frame.line
            is WireFrame.HttpError -> frame.body
        }
        val parsed = payload.takeUnless(String::isBlank)
            ?.let { runCatching { JsonUtil.json.parseToJsonElement(it).jsonObject }.getOrNull() }
        return ModelEvent.ProviderEvent(
            providerId = ProviderId.Anthropic,
            protocolId = ProtocolId.AnthropicMessages,
            type = when (frame) {
                is WireFrame.Sse -> frame.event ?: parsed.str("type") ?: "unknown"
                is WireFrame.Body -> parsed.str("type") ?: "unknown"
                is WireFrame.Ndjson -> parsed.str("type") ?: "unknown"
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
