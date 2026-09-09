package org.koaks.framework.middleware

import org.koaks.framework.loop.AgentState
import org.koaks.framework.loop.ExecutionIdentity
import org.koaks.framework.memory.MemoryView
import org.koaks.framework.memory.ThreadMemory
import org.koaks.framework.model.ModelItem
import org.koaks.framework.policy.TerminationReason

enum class StepPhase { BeforeModelRequest, AfterModelResponse, AfterStep }

/** Memory access scoped to a step callback. Same-ref appends update an existing item. */
class StepMemory internal constructor(private val delegate: ThreadMemory) {
    private var active = true
    suspend fun load(query: List<ModelItem> = emptyList()): MemoryView {
        check(active) { "step memory is no longer active" }
        return delegate.load(query)
    }
    suspend fun append(item: ModelItem) {
        check(active) { "step memory is no longer active" }
        delegate.append(item)
    }
    internal fun close() { active = false }
}

data class StepHookContext(
    val state: AgentState,
    val stage: StepPhase,
    val phase: ModelCallPhase,
    val memory: StepMemory,
    val execution: ExecutionIdentity?,
    val retry: Int = 0,
)

sealed interface StepDecision {
    data object Continue : StepDecision
    data class Stop(val reason: TerminationReason) : StepDecision
    data class Suspend(val reason: String) : StepDecision
}
