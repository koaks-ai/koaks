package org.koaks.framework.loop

import org.koaks.framework.model.Message
import org.koaks.framework.model.ModelEvent
import org.koaks.framework.model.ToolCall
import org.koaks.framework.model.Usage

/**
 * Side-channel accumulator for one model step. The loop forwards (emits)
 * [ModelEvent]s immediately for streaming, while feeding them here in parallel to
 * build the terminal assistant message and assembled tool calls.
 * It does NOT emit anything itself.
 */
class TurnAccumulator {
    private val text = StringBuilder()
    private val toolCalls = LinkedHashMap<String, ToolCallBuilder>()
    private var usage: Usage = Usage.ZERO

    fun observe(event: ModelEvent) {
        when (event) {
            is ModelEvent.TextDelta -> text.append(event.text)
            is ModelEvent.ToolCallDelta -> {
                val key = event.id.ifBlank { "idx-${event.index ?: 0}" }
                toolCalls.getOrPut(key) { ToolCallBuilder() }
                    .mergeDelta(event.id.ifBlank { null }, event.nameDelta, event.argumentsDelta)
            }

            is ModelEvent.ToolCallCompleted -> {
                val key = event.call.id.ifBlank { "idx-${toolCalls.size}" }
                toolCalls.getOrPut(key) { ToolCallBuilder() }.mergeComplete(event.call)
            }

            is ModelEvent.Completed -> usage = event.usage
            is ModelEvent.Failed -> {}
        }
    }

    fun assistantMessage(): Message = Message.assistant(text.toString(), toolCalls())

    /**
     * Assembled tool calls for the terminal step. Builders with a blank name are
     * dropped: a streamed fragment carrying neither id nor name is a phantom (e.g. a
     * model that hallucinates a stray `tool_calls` entry at a second index). The wire
     * decoder already discards these at `finish()`, so the authoritative
     * [ModelEvent.ToolCallCompleted] never arrives for them — only the orphan
     * [ModelEvent.ToolCallDelta] does. A nameless call can never be dispatched; surfacing
     * it would fabricate a `ToolNotFound("")` failure that the model never truly requested.
     */
    fun toolCalls(): List<ToolCall> = toolCalls.values
        .map { it.build() }
        .filter { it.name.isNotBlank() }

    fun usage(): Usage = usage
}
