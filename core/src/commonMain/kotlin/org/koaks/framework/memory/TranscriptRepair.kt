package org.koaks.framework.memory

import org.koaks.framework.model.ItemRef
import org.koaks.framework.model.ModelItem

const val INTERRUPTED_TOOL_OUTPUT: String = "<interrupted: not executed>"

/**
 * Projects a stored transcript into a provider-legal online view.
 *
 * Storage keeps orphan tool calls and partial assistant text as-is (audit truth).
 * The online view inserts a synthetic error [ModelItem.ToolResult] before the next
 * message (or at end of input) for every unresolved call so providers see legal order.
 * Injected results are not persisted, so a later policy can still re-execute.
 */
fun repairTranscript(items: List<ModelItem>): List<ModelItem> {
    val pending = LinkedHashMap<ItemRef, ModelItem.ToolCall>()
    val results = items.filterIsInstance<ModelItem.ToolResult>().associateBy { it.callRef }
    val emittedResults = HashSet<ItemRef>()
    val repaired = ArrayList<ModelItem>(items.size)

    fun flushPending() {
        pending.values.forEach { call ->
            // A child can append its conversation while its parent's tool is running.
            // Move a real, later result next to its call in the model projection only.
            val result = results[call.ref] ?: ModelItem.ToolResult(
                ref = ItemRef("repair_${call.ref.value}"),
                callRef = call.ref,
                output = INTERRUPTED_TOOL_OUTPUT,
                isError = true,
            )
            repaired += result
            emittedResults += result.ref
        }
        pending.clear()
    }

    for (item in items) {
        if (item is ModelItem.Message && pending.isNotEmpty()) flushPending()
        when (item) {
            is ModelItem.ToolCall -> pending[item.ref] = item
            is ModelItem.ToolResult -> {
                pending.remove(item.callRef)
                if (!emittedResults.add(item.ref)) continue
            }
            else -> Unit
        }
        repaired += item
    }
    flushPending()
    return repaired
}

fun unresolvedCallRefs(items: List<ModelItem>): List<ItemRef> {
    val resolved = items.filterIsInstance<ModelItem.ToolResult>().map { it.callRef }.toHashSet()
    return items.filterIsInstance<ModelItem.ToolCall>().map { it.ref }.filter { it !in resolved }
}
