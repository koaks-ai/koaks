package org.koaks.provider.ollama

import kotlinx.serialization.json.JsonObject
import org.koaks.framework.model.AgentError
import org.koaks.framework.model.ModelEvent
import org.koaks.framework.model.ToolCall
import org.koaks.framework.model.Usage
import org.koaks.framework.provider.WireDecoder

/**
 * Stateful decoder for Ollama's NDJSON `/api/chat` stream.
 *
 *  - `message.content` deltas → [ModelEvent.TextDelta] (forwarded immediately)
 *  - `message.thinking` deltas → [ModelEvent.ReasoningDelta] (forwarded immediately)
 *  - `message.tool_calls` arrive complete (Ollama does not fragment them); each is
 *    accumulated and emitted as [ModelEvent.ToolCallCompleted] at [finish]
 *  - eval counters on the `done` chunk → [ModelEvent.Completed]
 *  - `error` → [ModelEvent.Failed]
 *
 * Ollama omits tool-call ids, so a stable synthetic id (`call_<n>`) is assigned by
 * arrival order — the loop only needs it to correlate the result message.
 */
class OllamaWireDecoder : WireDecoder<OllamaChatResponse> {

    private val toolCalls = mutableListOf<ToolCall>()
    private var usage: Usage = Usage.ZERO
    private var failed: AgentError.ModelError? = null

    override fun accept(chunk: OllamaChatResponse): List<ModelEvent> {
        val events = mutableListOf<ModelEvent>()

        chunk.error?.let { err ->
            failed = AgentError.ModelError(message = err, retriable = false)
            events += ModelEvent.Failed(failed!!)
            return events
        }

        if (chunk.done) {
            usage = Usage(
                promptTokens = chunk.promptEvalCount ?: 0,
                completionTokens = chunk.evalCount ?: 0,
                totalTokens = (chunk.promptEvalCount ?: 0) + (chunk.evalCount ?: 0),
            )
        }

        val message = chunk.message ?: return events

        message.thinking?.let { if (it.isNotEmpty()) events += ModelEvent.ReasoningDelta(it) }
        if (message.content.isNotEmpty()) events += ModelEvent.TextDelta(message.content)

        message.toolCalls?.forEach { tc ->
            val id = "call_${toolCalls.size}"
            toolCalls += ToolCall(
                id = id,
                name = tc.function.name,
                arguments = encodeArguments(tc.function.arguments),
            )
            events += ModelEvent.ToolCallDelta(
                id = id,
                index = toolCalls.size - 1,
                nameDelta = tc.function.name,
                argumentsDelta = encodeArguments(tc.function.arguments),
            )
        }

        return events
    }

    override fun finish(): List<ModelEvent> {
        if (failed != null) return emptyList()
        val events = mutableListOf<ModelEvent>()
        toolCalls.forEach { events += ModelEvent.ToolCallCompleted(it) }
        events += ModelEvent.Completed(usage)
        return events
    }

    private fun encodeArguments(args: JsonObject): String = args.toString()
}
