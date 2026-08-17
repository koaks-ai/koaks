package org.koaks.framework.loop

import org.koaks.framework.model.AgentError
import org.koaks.framework.model.IncompleteReason
import org.koaks.framework.model.ItemRef
import org.koaks.framework.model.ModelEvent
import org.koaks.framework.model.ModelItem
import org.koaks.framework.model.ToolCall
import org.koaks.framework.model.Usage
import org.koaks.framework.middleware.ModelCallPhase
import org.koaks.framework.policy.TerminationReason
import org.koaks.framework.tool.ToolOutcome

sealed interface AgentEvent {
    data class TextDelta(val text: String, val itemRef: ItemRef? = null) : AgentEvent

    data class ReasoningDelta(
        val text: String,
        val itemRef: ItemRef? = null,
        val kind: ModelEvent.ReasoningKind = ModelEvent.ReasoningKind.RAW,
    ) : AgentEvent

    /** Detailed model event surfaced only for lossless runs. */
    data class Model(
        val event: ModelEvent,
        val step: Int,
        val phase: ModelCallPhase,
    ) : AgentEvent

    data class ToolCallRequested(val call: ToolCall) : AgentEvent

    data class ToolResult(val callId: String, val output: String, val isError: Boolean) : AgentEvent

    data class ToolProgress(val callId: String, val progress: org.koaks.framework.tool.ToolProgress) : AgentEvent

    data class StepCompleted(val step: Int) : AgentEvent

    sealed interface Terminal : AgentEvent {
        val message: ModelItem.Message
        val usage: Usage
    }

    data class Completed(
        override val message: ModelItem.Message,
        override val usage: Usage,
    ) : Terminal

    data class Incomplete(
        override val message: ModelItem.Message,
        override val usage: Usage,
        val reason: IncompleteReason,
    ) : Terminal

    data class Terminated(
        override val message: ModelItem.Message,
        override val usage: Usage,
        val reason: TerminationReason,
    ) : Terminal

    data class Failed(val error: AgentError, val usage: Usage = Usage.ZERO) : AgentEvent
}

internal fun ToolOutcome.toEvent(callId: String): AgentEvent = when (this) {
    is ToolOutcome.Success -> AgentEvent.ToolResult(callId, output, isError = false)
    is ToolOutcome.Failure -> AgentEvent.ToolResult(callId, error.message, isError = true)
}
