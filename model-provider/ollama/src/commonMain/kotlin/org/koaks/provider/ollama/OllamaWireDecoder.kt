package org.koaks.provider.ollama

import kotlinx.serialization.json.JsonObject
import org.koaks.framework.model.AgentError
import org.koaks.framework.model.ItemRef
import org.koaks.framework.model.ModelEvent
import org.koaks.framework.model.ModelItem
import org.koaks.framework.model.ModelResponse
import org.koaks.framework.model.ProviderId
import org.koaks.framework.model.ProviderScopedId
import org.koaks.framework.model.ToolCall
import org.koaks.framework.model.Usage
import org.koaks.framework.provider.WireDecoder
import org.koaks.framework.transport.WireFrame
import org.koaks.framework.utils.json.JsonUtil

/**
 * Stateful decoder for Ollama's NDJSON `/api/chat` stream.
 *
 * Tool-call ids are core [ItemRef]s. Ollama has no native call id, so a unique
 * [ItemRef] is assigned per call instead of recycling `call_0` across turns.
 */
class OllamaWireDecoder : WireDecoder {

    private val toolCalls = mutableListOf<ToolCall>()
    private val text = StringBuilder()
    private val output = mutableListOf<ModelItem>()
    private var usage: Usage = Usage.ZERO
    private var failed: AgentError.ModelError? = null
    private var finished = false
    private var started = false

    override fun accept(frame: WireFrame): List<ModelEvent> {
        val json = when (frame) {
            is WireFrame.Ndjson -> frame.line
            is WireFrame.Sse -> frame.data
            is WireFrame.Body -> frame.text
            is WireFrame.HttpError -> {
                failed = AgentError.ModelError(
                    message = "HTTP ${frame.status}: ${frame.body}",
                    retriable = frame.status >= 500,
                )
                return finishFailed()
            }
        }
        if (json.isBlank()) return emptyList()
        val chunk = runCatching {
            JsonUtil.fromJson(json, OllamaChatResponse.serializer())
        }.getOrElse { return emptyList() }
        return acceptChunk(chunk)
    }

    fun acceptChunk(chunk: OllamaChatResponse): List<ModelEvent> {
        val events = mutableListOf<ModelEvent>()
        if (!started) {
            started = true
            events += ModelEvent.Started(null)
        }

        chunk.error?.let { err ->
            failed = AgentError.ModelError(message = err, retriable = false)
            return events + finishFailed()
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
        if (message.content.isNotEmpty()) {
            text.append(message.content)
            events += ModelEvent.TextDelta(message.content)
        }

        message.toolCalls?.forEach { tc ->
            val ref = ItemRef.generate("call")
            val call = ToolCall(
                id = ref.value,
                name = tc.function.name,
                arguments = encodeArguments(tc.function.arguments),
                nativeId = ProviderScopedId(ProviderId.Ollama, ref.value),
            )
            toolCalls += call
            events += ModelEvent.ToolCallDelta(
                id = call.id,
                index = toolCalls.lastIndex,
                nameDelta = tc.function.name,
                argumentsDelta = call.arguments,
                itemRef = ref,
            )
        }

        return events
    }

    override fun finish(): List<ModelEvent> {
        if (finished) return emptyList()
        if (failed != null) return finishFailed()
        val events = mutableListOf<ModelEvent>()
        if (text.isNotEmpty()) output += ModelItem.assistant(text.toString())
        toolCalls.forEach { call ->
            output += call.toItem()
            events += ModelEvent.ToolCallCompleted(call)
        }
        finished = true
        events += ModelEvent.Finished(ModelResponse.Completed(output = output.toList(), usage = usage))
        return events
    }

    private fun finishFailed(): List<ModelEvent> {
        if (finished) return emptyList()
        finished = true
        return listOf(ModelEvent.Finished(ModelResponse.Failed(error = failed!!, usage = usage)))
    }

    private fun encodeArguments(args: JsonObject): String = args.toString()
}
