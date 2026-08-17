package org.koaks.framework.loop

import org.koaks.framework.memory.ConversationTurn
import org.koaks.framework.memory.InterruptReason
import org.koaks.framework.memory.PendingWork
import org.koaks.framework.memory.TurnStatus
import org.koaks.framework.memory.unresolvedCallRefs
import org.koaks.framework.model.ItemRef
import org.koaks.framework.model.ModelEvent
import org.koaks.framework.model.ModelItem
import org.koaks.framework.model.ModelResponse
import org.koaks.framework.model.ProviderCheckpoint
import org.koaks.framework.model.ToolCall
import org.koaks.framework.model.Usage
import org.koaks.framework.model.toDispatchCall

/**
 * Always-valid snapshot of the current turn. The runner feeds it [ModelEvent]s as
 * they arrive so a cancellation path can persist an [TurnStatus.Interrupted]
 * record without waiting for a terminal [ModelResponse].
 */
class TurnBuilder(
    private val turnId: String,
    seed: List<ModelItem> = emptyList(),
) {
    private val items = seed.toMutableList()
    private val text = StringBuilder()
    private var textRef: ItemRef? = null
    private val streamedTextRefs = HashSet<ItemRef>()
    private val pendingToolCalls = LinkedHashMap<String, ToolCallBuilder>()
    private var usage: Usage = Usage.ZERO
    private var checkpoint: ProviderCheckpoint? = null
    private var responseId: String? = null
    private var finished: ModelResponse? = null

    fun observe(event: ModelEvent) {
        when (event) {
            is ModelEvent.Started -> responseId = event.responseId
            is ModelEvent.CheckpointUpdated -> checkpoint = event.checkpoint
            is ModelEvent.TextDelta -> {
                if (text.isNotEmpty() && event.itemRef != null && event.itemRef != textRef) {
                    flushPartialText()
                }
                if (textRef == null) textRef = event.itemRef ?: ItemRef.generate("msg")
                streamedTextRefs += requireNotNull(textRef)
                text.append(event.text)
            }
            is ModelEvent.ReasoningDelta -> Unit
            is ModelEvent.RefusalDelta -> Unit
            is ModelEvent.AnnotationAdded -> Unit
            is ModelEvent.ItemAdded -> {
                flushPartialText()
                upsert(event.item)
            }
            is ModelEvent.ToolCallDelta -> {
                val key = event.itemRef?.value ?: event.id.ifBlank { "idx-${event.index ?: 0}" }
                pendingToolCalls.getOrPut(key) { ToolCallBuilder() }
                    .mergeDelta(event.id.ifBlank { null }, event.nameDelta, event.argumentsDelta)
            }
            is ModelEvent.ToolCallCompleted -> {
                flushPartialText()
                val key = event.call.ref.value.ifBlank { event.call.id.ifBlank { "idx-${pendingToolCalls.size}" } }
                pendingToolCalls.getOrPut(key) { ToolCallBuilder() }.mergeComplete(event.call)
                upsert(event.call.toItem())
            }
            is ModelEvent.ProviderEvent -> Unit
            is ModelEvent.Finished -> {
                flushPartialText()
                finished = event.response
                usage += event.response.usage
                event.response.checkpoint?.let { checkpoint = it }
                responseId = event.response.id ?: responseId
                mergeResponseOutput(event.response)
            }
        }
    }

    fun append(item: ModelItem) {
        upsert(item)
    }

    fun addUsage(delta: Usage) {
        usage += delta
    }

    fun toolCalls(): List<ToolCall> = pendingToolCalls.values
        .map { it.build() }
        .filter { it.name.isNotBlank() }

    fun consumeToolCalls(): List<ToolCall> {
        val calls = toolCalls()
        pendingToolCalls.clear()
        return calls
    }

    fun assistantMessage(): ModelItem.Message {
        flushPartialText()
        return items.filterIsInstance<ModelItem.Message>()
            .lastOrNull { it.role == org.koaks.framework.model.Role.ASSISTANT }
            ?: ModelItem.assistant(text.toString())
    }

    fun usage(): Usage = usage

    fun snapshot(): List<ModelItem> = items.toList()

    fun lastResponseId(): String? = responseId

    fun lastCheckpoint(): ProviderCheckpoint? = checkpoint

    fun lastResponse(): ModelResponse? = finished

    /** Returns terminal output reconciled with stream-hook transformations. */
    fun reconciledOutput(response: ModelResponse): List<ModelItem> = response.output.map { item ->
        items.firstOrNull { it.ref == item.ref } ?: item
    }

    fun snapshotTurn(status: TurnStatus): ConversationTurn = ConversationTurn(
        id = turnId,
        status = status,
        items = items.toList(),
        checkpoint = checkpoint,
        usage = usage,
    )

    fun completedTurn(): ConversationTurn = snapshotTurn(TurnStatus.Completed)

    /**
     * Structured finalization: drop scratch assistant text (and an optional schema
     * prompt) so the committed turn keeps the user/tool trace plus the final JSON.
     */
    fun collapseToFinalAssistant(finalAssistant: ModelItem.Message, dropRefs: Set<ItemRef> = emptySet()) {
        flushPartialText()
        if (dropRefs.isNotEmpty()) items.removeAll { it.ref in dropRefs }
        val lastUser = items.indexOfLast { it is ModelItem.Message && it.role == org.koaks.framework.model.Role.USER }
        val head = if (lastUser >= 0) items.take(lastUser + 1) else emptyList()
        val tail = items.drop(head.size).filter { it !is ModelItem.Message || it.role != org.koaks.framework.model.Role.ASSISTANT }
        items.clear()
        items.addAll(head)
        items.addAll(tail)
        items += finalAssistant
        text.clear()
        textRef = null
    }

    fun interruptedTurn(reason: InterruptReason): ConversationTurn {
        val partialText = text.toString().ifEmpty { null }
        val partialItem = if (partialText != null) {
            textRef ?: ItemRef.generate("msg").also { textRef = it }
        } else {
            null
        }
        flushPartialText()
        val pending = PendingWork(
            unresolvedCalls = unresolvedCallRefs(items),
            partialText = partialText,
            partialItem = partialItem,
        )
        return snapshotTurn(TurnStatus.Interrupted(reason, pending))
    }

    private fun flushPartialText() {
        if (text.isEmpty()) return
        upsert(ModelItem.assistant(text.toString(), ref = textRef ?: ItemRef.generate("msg")))
        text.clear()
        textRef = null
    }

    private fun mergeResponseOutput(response: ModelResponse) {
        response.output.forEach(::upsert)
    }

    private fun upsert(item: ModelItem) {
        val index = items.indexOfFirst { it.ref == item.ref }
        if (index < 0) {
            items += item
            return
        }
        val existing = items[index]
        items[index] = if (
            item is ModelItem.Message &&
            existing is ModelItem.Message &&
            item.ref in streamedTextRefs
        ) {
            item.copy(content = existing.content)
        } else {
            item
        }
    }
}
