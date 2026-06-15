package org.koaks.framework.middleware

import kotlinx.coroutines.flow.Flow
import org.koaks.framework.loop.AgentState
import org.koaks.framework.model.ModelEvent
import org.koaks.framework.model.ToolCall
import org.koaks.framework.tool.ToolOutcome

/** Context handed to [AgentMiddleware.aroundModelCall]. */
data class StepContext(val state: AgentState)

/** Context handed to [AgentMiddleware.aroundToolCall]. */
data class ToolContext(val call: ToolCall, val state: AgentState)

/**
 * Around-style cross-cutting extension point (tracing-of-flow, cache, guardrail,
 * tool timeout). Covers ONLY around-type concerns; control-flow decisions
 * (returnDirectly / handoff / error recovery) live in the loop, not here.
 *
 * Contract for [aroundModelCall]: an implementation must EITHER return `next()`
 * verbatim (pass-through) OR return a self-made flow (cache hit / short-circuit,
 * never calling the model). It must NEVER collect `next()` — that would subscribe
 * the cold flow twice and fire the model request twice. To observe events, use
 * [AgentListener] instead.
 */
interface AgentMiddleware {
    suspend fun aroundModelCall(ctx: StepContext, next: suspend () -> Flow<ModelEvent>): Flow<ModelEvent> = next()
    suspend fun aroundToolCall(ctx: ToolContext, next: suspend () -> ToolOutcome): ToolOutcome = next()
}
