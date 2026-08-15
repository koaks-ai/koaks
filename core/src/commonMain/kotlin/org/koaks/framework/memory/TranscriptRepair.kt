package org.koaks.framework.memory

import org.koaks.framework.model.ItemRef
import org.koaks.framework.model.ModelItem

const val INTERRUPTED_TOOL_OUTPUT: String = "<interrupted: not executed>"

/**
 * Projects a stored transcript into a provider-legal online view.
 *
 * Storage keeps orphan tool calls and partial assistant text as-is (audit truth).
 * The online view appends a synthetic error [ModelItem.ToolResult] for every
 * unresolved call so Chat Completions / Anthropic / Responses all accept the replay.
 * Injected results are not persisted, so a later policy can still re-execute.
 */
fun repairTranscript(items: List<ModelItem>): List<ModelItem> {
    val resolved = items.filterIsInstance<ModelItem.ToolResult>().map { it.callRef }.toHashSet()
    val orphans = items.filterIsInstance<ModelItem.ToolCall>().filter { it.ref !in resolved }
    if (orphans.isEmpty()) return items
    return items + orphans.map { call ->
        ModelItem.ToolResult(
            ref = ItemRef.generate("repair"),
            callRef = call.ref,
            output = INTERRUPTED_TOOL_OUTPUT,
            isError = true,
        )
    }
}

fun unresolvedCallRefs(items: List<ModelItem>): List<ItemRef> {
    val resolved = items.filterIsInstance<ModelItem.ToolResult>().map { it.callRef }.toHashSet()
    return items.filterIsInstance<ModelItem.ToolCall>().map { it.ref }.filter { it !in resolved }
}
