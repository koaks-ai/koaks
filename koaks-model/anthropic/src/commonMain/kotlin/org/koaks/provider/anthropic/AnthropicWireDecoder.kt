package org.koaks.provider.anthropic

import org.koaks.framework.model.AgentError
import org.koaks.framework.model.ModelEvent
import org.koaks.framework.model.ToolCall
import org.koaks.framework.model.Usage
import org.koaks.framework.provider.WireDecoder

/**
 * Stateful decoder for Anthropic's Messages API stream.
 *
 *  - `text_delta` → [ModelEvent.TextDelta] (forwarded immediately)
 *  - `thinking_delta` → [ModelEvent.ReasoningDelta] (forwarded immediately)
 *  - `tool_use` blocks: id + name arrive on `content_block_start`, the `input` object
 *    arrives as `input_json_delta` fragments — accumulated by content-block `index`
 *    and emitted as [ModelEvent.ToolCallCompleted] at [finish]
 *  - `message_start` usage (input tokens) + `message_delta` usage (output tokens)
 *    → [ModelEvent.Completed]
 *  - `error` → [ModelEvent.Failed]
 */
class AnthropicWireDecoder : WireDecoder<AnthropicChatResponse> {

    private class ToolAcc(val id: String, val name: String, val args: StringBuilder = StringBuilder())

    private val toolCalls = LinkedHashMap<Int, ToolAcc>()
    private var promptTokens = 0
    private var completionTokens = 0
    private var failed: AgentError.ModelError? = null

    override fun accept(chunk: AnthropicChatResponse): List<ModelEvent> {
        val events = mutableListOf<ModelEvent>()

        when (chunk.type) {
            "error" -> {
                failed = AgentError.ModelError(
                    message = chunk.error?.message ?: "anthropic error ${chunk.error?.type}",
                    retriable = false,
                )
                events += ModelEvent.Failed(failed!!)
                return events
            }

            "message_start" -> {
                chunk.message?.usage?.inputTokens?.let { promptTokens = it }
            }

            "content_block_start" -> {
                val block = chunk.contentBlock
                if (block?.type == "tool_use") {
                    val index = chunk.index ?: toolCalls.size
                    toolCalls[index] = ToolAcc(id = block.id ?: "", name = block.name ?: "")
                }
            }

            "content_block_delta" -> {
                val delta = chunk.delta
                when (delta?.type) {
                    "text_delta" -> delta.text?.let { if (it.isNotEmpty()) events += ModelEvent.TextDelta(it) }
                    "thinking_delta" -> delta.thinking?.let { if (it.isNotEmpty()) events += ModelEvent.ReasoningDelta(it) }
                    "input_json_delta" -> {
                        val index = chunk.index ?: return events
                        val acc = toolCalls[index] ?: return events
                        val fragment = delta.partialJson ?: return events
                        acc.args.append(fragment)
                        events += ModelEvent.ToolCallDelta(
                            id = acc.id,
                            index = index,
                            argumentsDelta = fragment,
                        )
                    }
                }
            }

            "message_delta" -> {
                chunk.usage?.outputTokens?.let { completionTokens = it }
            }
            // content_block_stop / message_stop / ping carry nothing we accumulate.
        }

        return events
    }

    override fun finish(): List<ModelEvent> {
        if (failed != null) return emptyList()
        val events = mutableListOf<ModelEvent>()
        // Emit in content-block index order — Anthropic assigns each tool_use a stable index.
        toolCalls.entries.sortedBy { it.key }.forEach { (_, acc) ->
            events += ModelEvent.ToolCallCompleted(
                ToolCall(
                    id = acc.id,
                    name = acc.name,
                    arguments = acc.args.toString().ifBlank { "{}" },
                )
            )
        }
        events += ModelEvent.Completed(
            Usage(
                promptTokens = promptTokens,
                completionTokens = completionTokens,
                totalTokens = promptTokens + completionTokens,
            )
        )
        return events
    }
}
