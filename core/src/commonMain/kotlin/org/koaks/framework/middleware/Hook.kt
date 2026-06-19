package org.koaks.framework.middleware

import kotlinx.coroutines.flow.Flow
import org.koaks.framework.loop.AgentState
import org.koaks.framework.model.ChatRequest
import org.koaks.framework.model.ModelEvent
import org.koaks.framework.model.ToolCall
import org.koaks.framework.tool.ToolOutcome

/** Distinguishes normal loop model calls from the structured-output finalization call. */
enum class ModelCallPhase {
    Normal,
    StructuredFinalization,
}

/** Context handed to [Hook.onModelRequest] and [Hook.onModelStream]. */
data class StepContext(
    val state: AgentState,
    val request: ChatRequest,
    val phase: ModelCallPhase = ModelCallPhase.Normal,
)

/** Context handed to [Hook.onToolCall] and [Hook.onToolResult]. */
data class ToolContext(val call: ToolCall, val state: AgentState)

/**
 * Typed interception point for agent model/tool calls.
 *
 * Model stream hooks must return a transformed flow without collecting it. Use lazy
 * Flow operators such as `map`, `onEach`, `transform`, `catch`, or `onCompletion`
 * so the runner remains the single subscriber to the model stream.
 *
 * Hooks compose as an onion (install order in, reverse order out). For tool calls
 * this is symmetric: if a hook's [onToolCall] returns [ToolDecision.Deny], the hooks
 * after it never enter, so only the hooks whose [onToolCall] ran unwind through
 * [onToolResult] — a skipped hook's result side is never invoked.
 *
 * A hook that throws is not fatal: the runner routes the failure through the unified
 * error channel. A throwing [onToolCall]/[onToolResult] becomes a tool failure
 * ([org.koaks.framework.tool.ToolOutcome.Failure]); a throwing [onModelRequest]/[onModelStream]
 * is mapped to an [org.koaks.framework.model.AgentError] and run through the agent's
 * error policy, exactly like a model failure. [kotlinx.coroutines.CancellationException]
 * is always re-thrown, never swallowed.
 */
interface Hook {
    suspend fun onModelRequest(ctx: StepContext): ChatRequest = ctx.request
    fun onModelStream(ctx: StepContext, events: Flow<ModelEvent>): Flow<ModelEvent> = events
    suspend fun onToolCall(ctx: ToolContext): ToolDecision = ToolDecision.Proceed
    suspend fun onToolResult(ctx: ToolContext, outcome: ToolOutcome): ToolOutcome = outcome
}
