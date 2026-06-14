package org.koaks.framework.loop

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import org.koaks.framework.middleware.StepContext
import org.koaks.framework.middleware.ToolContext
import org.koaks.framework.model.AgentError
import org.koaks.framework.model.ModelEvent
import org.koaks.framework.policy.Recovery
import org.koaks.framework.tool.ToolOutcome

/**
 * The independent, strongly-typed agent loop. NOT graph-based — it is just a
 * `while` with a model step, a tool step, and a "more tool calls?" branch.
 *
 * The critical behaviors (design §4):
 *  - **tee streaming**: while collecting model events it forwards (`emit`)
 *    TextDelta/ToolCallRequested immediately AND accumulates terminal state in
 *    parallel — never collect-then-emit.
 *  - **explicit failure channel**: tool-not-found is a [ToolOutcome.Failure], surfaced
 *    via [AgentEvent.Failed] / an isError tool message — never a fabricated result.
 *  - **cancellation propagates**: [CancellationException] is re-thrown, never recovered.
 *  - **single AgentError decision point**: provider failures arrive as [ModelFailure]
 *    carrying the original [AgentError]; real exceptions are mapped once.
 */
class AgentRunner(private val agent: Agent) {

    fun stream(initial: List<org.koaks.framework.model.Message>): Flow<AgentEvent> = flow {
        var state = AgentState(messages = initial, activeAgentName = agent.name)
        var retries = 0

        while (!agent.termination.shouldStop(state)) {
            val acc = TurnAccumulator()
            agent.listeners.forEach { it.onStep(state) }

            // model step — middleware only wraps (selects the flow), it never collects it.
            val source: Flow<ModelEvent> = agent.middlewares.foldRight(
                { agent.model.generate(agent.toRequest(state)) }
            ) { mw, next: suspend () -> Flow<ModelEvent> ->
                { mw.aroundModelCall(StepContext(state), next) }
            }()

            var emittedText = false
            try {
                source.collect { event ->
                    acc.observe(event)
                    agent.listeners.forEach { it.onModelEvent(event) }
                    when (event) {
                        is ModelEvent.TextDelta -> {
                            emittedText = true
                            emitEvent(AgentEvent.TextDelta(event.text))
                        }
                        is ModelEvent.ToolCallCompleted ->
                            emitEvent(AgentEvent.ToolCallRequested(event.call))
                        is ModelEvent.Failed ->
                            throw ModelFailure(event.error)
                        else -> {}
                    }
                }
            } catch (t: Throwable) {
                val error: AgentError = when (t) {
                    is ModelFailure -> t.error
                    is CancellationException -> throw t
                    else -> t.toAgentError()
                }
                // A retry that re-runs the step is only safe before any TextDelta was emitted.
                when (val r = agent.errorPolicy.decide(error, state)) {
                    is Recovery.Retry -> {
                        if (!emittedText && retries < r.maxRetries) {
                            retries++
                            delay(r.delayMs)
                            continue
                        }
                        emitEvent(AgentEvent.Failed(error)); return@flow
                    }
                    is Recovery.Substitute -> {
                        state = state.append(r.message); continue
                    }
                    is Recovery.Propagate -> {
                        emitEvent(AgentEvent.Failed(error)); return@flow
                    }
                }
            }
            retries = 0

            val assistant = acc.assistantMessage()
            state = state.append(assistant).addUsage(acc.usage())
            emitEvent(AgentEvent.StepCompleted(state.step))

            val calls = acc.toolCalls()
            if (calls.isEmpty()) {
                emitEvent(AgentEvent.Finished(assistant, state.usage)); return@flow
            }

            // tool step — parallel; failures travel the explicit channel (isError tool message).
            val outcomes: List<ToolOutcome> = coroutineScope {
                calls.map { call ->
                    async {
                        val next: suspend () -> ToolOutcome = { agent.tools.call(call.name, call.arguments) }
                        val wrapped = agent.middlewares.foldRight(next) { mw, acc2 ->
                            { mw.aroundToolCall(ToolContext(call, state), acc2) }
                        }
                        wrapped()
                    }
                }.awaitAll()
            }
            outcomes.forEachIndexed { i, o ->
                val ev = o.toEvent(calls[i].id)
                emitEvent(ev)
                if (o is ToolOutcome.Failure) emitEvent(AgentEvent.Failed(o.error))
            }
            state = state.appendToolResults(calls, outcomes)

            // returnDirectly is loop control (§3.7): finish immediately, skip next model step.
            outcomes.firstOrNull { it is ToolOutcome.Success && it.returnDirectly }?.let { direct ->
                val out = (direct as ToolOutcome.Success).output
                emitEvent(AgentEvent.Finished(org.koaks.framework.model.Message.assistant(out), state.usage))
                return@flow
            }
        }
        // termination policy hit (maxSteps/maxTokens): still give downstream a terminal event.
        emitEvent(AgentEvent.Finished(state.lastAssistantOrEmpty(), state.usage))
    }

    suspend fun run(initial: List<org.koaks.framework.model.Message>): AgentResult {
        val events = stream(initial).toList()
        val finished = events.filterIsInstance<AgentEvent.Finished>().lastOrNull()
        if (finished != null) return AgentResult(finished.message, finished.usage)
        val failed = events.filterIsInstance<AgentEvent.Failed>().lastOrNull()
        return AgentResult(
            message = org.koaks.framework.model.Message.assistant(""),
            usage = org.koaks.framework.model.Usage.ZERO,
            error = failed?.error,
        )
    }

    /** Helper to also notify listeners on each outgoing AgentEvent (single tee point). */
    private suspend fun kotlinx.coroutines.flow.FlowCollector<AgentEvent>.emitEvent(event: AgentEvent) {
        agent.listeners.forEach { it.onAgentEvent(event) }
        emit(event)
    }
}

private fun Throwable.toAgentError(): AgentError = AgentError.ModelError(
    message = message ?: "model call failed",
    retriable = false,
    cause = this,
)
