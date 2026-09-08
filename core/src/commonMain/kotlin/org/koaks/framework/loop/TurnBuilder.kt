package org.koaks.framework.loop

import org.koaks.framework.model.ItemRef
import org.koaks.framework.model.ModelEvent
import org.koaks.framework.model.ModelItem
import org.koaks.framework.model.ModelResponse
import org.koaks.framework.model.ProviderCheckpoint
import org.koaks.framework.model.ToolCall
import org.koaks.framework.model.toDispatchCall

/**
 * Assembles the current in-flight model step from [ModelEvent]s. Completed items are
 * taken by the runner and appended to memory; this builder never holds prior turns.
 */
class TurnBuilder {
    private val items = mutableListOf<ModelItem>()
    private val pending = LinkedHashMap<ItemRef, ModelItem>()
    private val text = StringBuilder()
    private var textRef: ItemRef? = null
    private val streamedTextRefs = HashSet<ItemRef>()
    private val pendingToolCalls = LinkedHashMap<String, ToolCallBuilder>()
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
                if (textRef == null) {
                    textRef = event.itemRef ?: ItemRef.generate("msg")
                    items.filterIsInstance<ModelItem.Message>().firstOrNull { it.ref == textRef }?.let { text.append(it.text) }
                }
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
                event.response.checkpoint?.let { checkpoint = it }
                responseId = event.response.id ?: responseId
                mergeResponseOutput(event.response)
            }
        }
    }

    fun toolCalls(): List<ToolCall> = pendingToolCalls.values
        .map { it.build() }
        .filter { it.name.isNotBlank() }

    fun assistantMessage(): ModelItem.Message {
        flushPartialText()
        return items.filterIsInstance<ModelItem.Message>()
            .lastOrNull { it.role == org.koaks.framework.model.Role.ASSISTANT }
            ?: ModelItem.assistant(text.toString())
    }

    fun lastResponseId(): String? = responseId

    fun lastCheckpoint(): ProviderCheckpoint? = checkpoint

    fun lastResponse(): ModelResponse? = finished

    fun snapshot(): List<ModelItem> = items.toList()

    /** Returns terminal output reconciled with stream-hook transformations. */
    fun reconciledOutput(response: ModelResponse): List<ModelItem> = response.output.map { item ->
        items.firstOrNull { it.ref == item.ref } ?: item
    }

    fun takeStepItems(): List<ModelItem> {
        flushPartialText()
        return items.toList()
    }

    /** Remaining streamed assistant text, if the step was interrupted before [ModelEvent.Finished]. */
    fun flushPartial(): ModelItem? {
        if (text.isEmpty()) return items.filterIsInstance<ModelItem.Message>()
            .lastOrNull { it.role == org.koaks.framework.model.Role.ASSISTANT }
        val item = ModelItem.assistant(text.toString(), ref = textRef ?: ItemRef.generate("msg"))
        text.clear()
        textRef = null
        upsert(item, streamedDraft = true)
        return item
    }

    /** Drains completed items once, retaining terminal metadata updates under the same ref. */
    fun drainCompletedItems(): List<ModelItem> = pending.values.toList().also { pending.clear() }

    fun reset() {
        items.clear()
        pending.clear()
        checkpoint = null
        responseId = null
        text.clear()
        textRef = null
        streamedTextRefs.clear()
        pendingToolCalls.clear()
        finished = null
    }

    private fun flushPartialText() {
        if (text.isEmpty()) return
        upsert(ModelItem.assistant(text.toString(), ref = textRef ?: ItemRef.generate("msg")), streamedDraft = true)
        text.clear()
        textRef = null
    }

    private fun mergeResponseOutput(response: ModelResponse) {
        response.output.forEach { upsert(it) }
    }

    private fun upsert(item: ModelItem, streamedDraft: Boolean = false) {
        val index = items.indexOfFirst { it.ref == item.ref }
        if (index < 0) {
            items += item
            pending[item.ref] = item
            return
        }
        val existing = items[index]
        val merged = if (
            !streamedDraft && item is ModelItem.Message &&
            existing is ModelItem.Message &&
            item.ref in streamedTextRefs
        ) {
            item.copy(content = existing.content)
        } else {
            item
        }
        items[index] = merged
        if (merged != existing) pending[item.ref] = merged
    }
}
