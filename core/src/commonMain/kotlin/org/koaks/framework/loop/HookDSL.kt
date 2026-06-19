package org.koaks.framework.loop

import kotlinx.coroutines.flow.Flow
import org.koaks.framework.middleware.Hook
import org.koaks.framework.middleware.StepContext
import org.koaks.framework.middleware.ToolContext
import org.koaks.framework.middleware.ToolDecision
import org.koaks.framework.model.ChatRequest
import org.koaks.framework.model.ModelEvent
import org.koaks.framework.model.ToolCall
import org.koaks.framework.tool.ToolOutcome

/** DSL scope for configuring typed agent hooks. */
@AgentDSL
class HookScope {
    private val modelBefores = mutableListOf<suspend (StepContext) -> ChatRequest>()
    private val modelAfters = mutableListOf<(StepContext, Flow<ModelEvent>) -> Flow<ModelEvent>>()
    private val toolBefores = mutableListOf<suspend (ToolContext) -> ToolDecision>()
    private val toolAfters = mutableListOf<suspend (ToolContext, ToolOutcome) -> ToolOutcome>()

    fun onModelCall(block: ModelCallHookScope.() -> Unit) {
        ModelCallHookScope(modelBefores, modelAfters).apply(block)
    }

    fun onToolCall(block: ToolCallHookScope.() -> Unit) {
        ToolCallHookScope(toolBefores, toolAfters).apply(block)
    }

    internal fun build(): Hook = object : Hook {
        override suspend fun onModelRequest(ctx: StepContext): ChatRequest {
            var current = ctx.request
            for (before in modelBefores) {
                current = before(ctx.copy(request = current))
            }
            return current
        }

        override fun onModelStream(ctx: StepContext, events: Flow<ModelEvent>): Flow<ModelEvent> {
            var current = events
            for (after in modelAfters) {
                current = after(ctx, current)
            }
            return current
        }

        override suspend fun onToolCall(ctx: ToolContext): ToolDecision {
            var current = ctx.call
            var changed = false
            for (before in toolBefores) {
                when (val decision = before(ctx.copy(call = current))) {
                    ToolDecision.Proceed -> {}
                    is ToolDecision.ProceedWith -> {
                        current = decision.call.copy(id = ctx.call.id)
                        changed = true
                    }
                    is ToolDecision.Deny -> return decision
                }
            }
            return if (changed) ToolDecision.ProceedWith(current) else ToolDecision.Proceed
        }

        override suspend fun onToolResult(ctx: ToolContext, outcome: ToolOutcome): ToolOutcome {
            var current = outcome
            for (after in toolAfters) {
                current = after(ctx, current)
            }
            return current
        }
    }
}

@AgentDSL
class ModelCallHookScope internal constructor(
    private val befores: MutableList<suspend (StepContext) -> ChatRequest>,
    private val afters: MutableList<(StepContext, Flow<ModelEvent>) -> Flow<ModelEvent>>,
) {
    fun before(block: suspend (StepContext) -> ChatRequest) {
        befores += block
    }

    fun after(block: (StepContext, Flow<ModelEvent>) -> Flow<ModelEvent>) {
        afters += block
    }
}

@AgentDSL
class ToolCallHookScope internal constructor(
    private val befores: MutableList<suspend (ToolContext) -> ToolDecision>,
    private val afters: MutableList<suspend (ToolContext, ToolOutcome) -> ToolOutcome>,
) {
    val Proceed: ToolDecision = ToolDecision.Proceed

    fun ProceedWith(call: ToolCall): ToolDecision = ToolDecision.ProceedWith(call)

    fun Deny(reason: String): ToolDecision = ToolDecision.Deny(reason)

    fun before(block: suspend (ToolContext) -> ToolDecision) {
        befores += block
    }

    fun after(block: suspend (ToolContext, ToolOutcome) -> ToolOutcome) {
        afters += block
    }
}
