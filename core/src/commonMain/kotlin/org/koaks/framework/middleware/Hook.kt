package org.koaks.framework.middleware

import kotlinx.coroutines.flow.Flow
import org.koaks.framework.loop.AgentState
import org.koaks.framework.model.ModelEvent
import org.koaks.framework.model.ModelRequest
import org.koaks.framework.model.ToolCall
import org.koaks.framework.tool.ToolOutcome

enum class ModelCallPhase {
    Normal,
    StructuredFinalization,
}

data class StepContext(
    val state: AgentState,
    val request: ModelRequest,
    val phase: ModelCallPhase = ModelCallPhase.Normal,
)

data class ToolContext(val call: ToolCall, val state: AgentState)

/**
 * Typed interception point for agent model/tool calls.
 *
 * Model stream hooks must return a transformed flow without collecting it. Use lazy
 * Flow operators so the runner remains the single subscriber to the model stream.
 */
interface Hook {
    suspend fun onModelRequest(ctx: StepContext): ModelRequest = ctx.request
    fun onModelStream(ctx: StepContext, events: Flow<ModelEvent>): Flow<ModelEvent> = events
    suspend fun onToolCall(ctx: ToolContext): ToolDecision = ToolDecision.Proceed
    suspend fun onToolResult(ctx: ToolContext, outcome: ToolOutcome): ToolOutcome = outcome
}
