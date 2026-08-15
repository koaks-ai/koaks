package org.koaks.provider.anthropic

import okio.ByteString.Companion.encodeUtf8
import org.koaks.framework.model.AgentError
import org.koaks.framework.model.ItemRef
import org.koaks.framework.model.ModelEvent
import org.koaks.framework.model.ModelItem
import org.koaks.framework.model.ModelResponse
import org.koaks.framework.model.ProviderId
import org.koaks.framework.model.ProviderScopedId
import org.koaks.framework.model.ReplayPolicy
import org.koaks.framework.model.ToolCall
import org.koaks.framework.model.Usage
import org.koaks.framework.provider.WireDecoder
import org.koaks.framework.transport.WireFrame
import org.koaks.framework.utils.json.JsonUtil

class AnthropicWireDecoder : WireDecoder {

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
        val json = when (frame) {
            is WireFrame.Sse -> frame.data
            is WireFrame.Body -> frame.text
            is WireFrame.Ndjson -> frame.line
            is WireFrame.HttpError -> {
                failed = AgentError.ModelError("HTTP ${frame.status}: ${frame.body}", retriable = frame.status >= 500)
                return finishFailed()
            }
        }
        if (json.isBlank()) return emptyList()
        val chunk = runCatching {
            JsonUtil.fromJson(json, AnthropicChatResponse.serializer())
        }.getOrElse { return emptyList() }
        return acceptChunk(chunk)
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
            "message_start" -> chunk.message?.usage?.inputTokens?.let { promptTokens = it }
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
                        chunk.index?.let { idx -> thinking[idx]?.text?.append(it) }
                        if (it.isNotEmpty()) events += ModelEvent.ReasoningDelta(it)
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
}
