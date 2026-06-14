package org.koaks.provider.qwen

import org.koaks.framework.model.AgentError
import org.koaks.framework.model.ModelEvent
import org.koaks.framework.model.ToolCall
import org.koaks.framework.model.Usage
import org.koaks.framework.provider.WireDecoder

/**
 * Stateful decoder for Qwen's OpenAI-compatible stream.
 *
 *  - assistant `content` deltas → [ModelEvent.TextDelta] (forwarded immediately)
 *  - tool call fragments (name/arguments split across chunks, keyed by `index`) are
 *    accumulated and emitted as [ModelEvent.ToolCallCompleted] at [finish]
 *  - `usage` → [ModelEvent.Completed]
 *  - `error` → [ModelEvent.Failed]
 *
 * Non-streaming (a single chunk with a full `message`) is handled by the same path.
 */
class QwenWireDecoder : WireDecoder<QwenChatResponse> {

    private class ToolAcc(var id: String? = null, val name: StringBuilder = StringBuilder(), val args: StringBuilder = StringBuilder())

    private val toolCalls = LinkedHashMap<Int, ToolAcc>()
    private var usage: Usage = Usage.ZERO
    private var failed: AgentError.ModelError? = null

    override fun accept(chunk: QwenChatResponse): List<ModelEvent> {
        val events = mutableListOf<ModelEvent>()

        chunk.error?.let { err ->
            failed = AgentError.ModelError(
                message = err.message ?: "qwen error ${err.code}",
                retriable = false,
            )
            events += ModelEvent.Failed(failed!!)
            return events
        }

        chunk.usage?.let {
            usage = Usage(
                promptTokens = it.promptTokens ?: 0,
                completionTokens = it.completionTokens ?: 0,
                totalTokens = it.totalTokens ?: 0,
            )
        }

        val choice = chunk.choices?.firstOrNull() ?: return events
        // delta (streaming) or message (non-streaming) carry the same shape.
        val payload = choice.delta ?: choice.message

        payload?.content?.let { if (it.isNotEmpty()) events += ModelEvent.TextDelta(it) }

        payload?.toolCalls?.forEach { tc ->
            val acc = toolCalls.getOrPut(tc.index) { ToolAcc() }
            tc.id?.let { if (acc.id == null) acc.id = it }
            tc.function?.name?.let { acc.name.append(it) }
            tc.function?.arguments?.let { acc.args.append(it) }
            events += ModelEvent.ToolCallDelta(
                id = tc.id ?: acc.id ?: "",
                index = tc.index,
                nameDelta = tc.function?.name,
                argumentsDelta = tc.function?.arguments,
            )
        }

        return events
    }

    override fun finish(): List<ModelEvent> {
        if (failed != null) return emptyList()
        val events = mutableListOf<ModelEvent>()
        toolCalls.values.forEach { acc ->
            events += ModelEvent.ToolCallCompleted(
                ToolCall(
                    id = acc.id ?: "",
                    name = acc.name.toString(),
                    arguments = acc.args.toString().ifBlank { "{}" },
                )
            )
        }
        events += ModelEvent.Completed(usage)
        return events
    }
}
