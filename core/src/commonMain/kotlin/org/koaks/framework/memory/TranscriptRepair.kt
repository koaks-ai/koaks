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
    var repaired: MutableList<ModelItem>? = null

    fun flushPending(target: MutableList<ModelItem>) {
        pending.values.forEach { call ->
            target += ModelItem.ToolResult(
                ref = ItemRef("repair_${call.ref.value}"),
                callRef = call.ref,
                output = INTERRUPTED_TOOL_OUTPUT,
                isError = true,
            )
        }
        pending.clear()
    }

    for ((index, item) in items.withIndex()) {
        if (item is ModelItem.Message && pending.isNotEmpty()) {
            val target = repaired ?: items.take(index).toMutableList().also { repaired = it }
            flushPending(target)
        }
        when (item) {
            is ModelItem.ToolCall -> pending[item.ref] = item
            is ModelItem.ToolResult -> pending.remove(item.callRef)
            else -> Unit
        }
        repaired?.add(item)
    }
    if (pending.isEmpty()) return repaired ?: items
    val target = repaired ?: items.toMutableList().also { repaired = it }
    flushPending(target)
    return target
}

fun unresolvedCallRefs(items: List<ModelItem>): List<ItemRef> {
    val resolved = items.filterIsInstance<ModelItem.ToolResult>().map { it.callRef }.toHashSet()
    return items.filterIsInstance<ModelItem.ToolCall>().map { it.ref }.filter { it !in resolved }
}
