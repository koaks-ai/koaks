package org.koaks.framework.loop

import org.koaks.framework.model.ModelItem
import org.koaks.framework.model.ProviderCheckpoint
import org.koaks.framework.model.Role
import org.koaks.framework.model.Usage

/**
 * Per-run loop counters. Conversation items live in [org.koaks.framework.memory.ThreadMemory].
 *
 * @property instructions resolved system instructions for this run.
 * @property checkpoint in-run provider checkpoint (not written to memory).
 */
data class AgentState(
    val instructions: String? = null,
    val checkpoint: ProviderCheckpoint? = null,
    val globalStep: Int = 0,
    val localStep: Int = 0,
    val usage: Usage = Usage.ZERO,
    val activeAgentName: String = "agent",
) {
    val step: Int get() = globalStep

    fun addUsage(delta: Usage): AgentState = copy(usage = usage + delta)

    fun withCheckpoint(checkpoint: ProviderCheckpoint?): AgentState = copy(checkpoint = checkpoint)

    fun advanceModelStep(): AgentState =
        copy(globalStep = globalStep + 1, localStep = localStep + 1)
}

fun List<ModelItem>.lastAssistantOrEmpty(): ModelItem.Message =
    filterIsInstance<ModelItem.Message>().lastOrNull { it.role == Role.ASSISTANT }
        ?: ModelItem.assistant("")
