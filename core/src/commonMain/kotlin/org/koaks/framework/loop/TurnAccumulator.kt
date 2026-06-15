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

    fun toolCalls(): List<ToolCall> = toolCalls.values.map { it.build() }

    fun usage(): Usage = usage
}
