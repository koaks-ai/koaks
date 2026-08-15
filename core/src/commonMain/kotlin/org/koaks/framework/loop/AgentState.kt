package org.koaks.framework.loop

import org.koaks.framework.model.ModelItem
import org.koaks.framework.model.ProviderCheckpoint
import org.koaks.framework.model.Role
import org.koaks.framework.model.ToolCall
import org.koaks.framework.model.Usage
import org.koaks.framework.tool.ToolOutcome

/**
 * The strongly-typed agent loop state — a `data class`, NOT a `Map<String, Any>`.
 *
 * @property items the working set for this run (repaired history + this run's items).
 * @property instructions resolved system instructions for this run.
 * @property checkpoint in-run provider checkpoint (never persisted unless CrossTurn).
 */
data class AgentState(
    val items: List<ModelItem>,
    val instructions: String? = null,
    val checkpoint: ProviderCheckpoint? = null,
    val globalStep: Int = 0,
    val localStep: Int = 0,
    val usage: Usage = Usage.ZERO,
    val activeAgentName: String = "agent",
) {
    val step: Int get() = globalStep

    fun append(item: ModelItem): AgentState =
        copy(items = items + item, globalStep = globalStep + 1, localStep = localStep + 1)

    fun appendAll(newItems: List<ModelItem>): AgentState =
        copy(items = items + newItems, globalStep = globalStep + newItems.size, localStep = localStep + newItems.size)

    fun addUsage(delta: Usage): AgentState = copy(usage = usage + delta)

    fun withCheckpoint(checkpoint: ProviderCheckpoint?): AgentState = copy(checkpoint = checkpoint)

    fun appendToolResults(calls: List<ToolCall>, outcomes: List<ToolOutcome>): AgentState {
        val results = calls.mapIndexed { i, call ->
            when (val o = outcomes[i]) {
                is ToolOutcome.Success -> ModelItem.ToolResult(
                    callRef = call.ref,
                    output = o.output,
                    isError = false,
                )
                is ToolOutcome.Failure -> ModelItem.ToolResult(
                    callRef = call.ref,
                    output = o.error.message,
                    isError = true,
                )
            }
        }
        return copy(items = items + results)
    }

    fun lastAssistantOrEmpty(): ModelItem.Message =
        items.filterIsInstance<ModelItem.Message>().lastOrNull { it.role == Role.ASSISTANT }
            ?: ModelItem.assistant("")
}
