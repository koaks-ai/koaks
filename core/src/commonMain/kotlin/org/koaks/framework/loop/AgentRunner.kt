package org.koaks.framework.loop

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.koaks.framework.middleware.StepContext
import org.koaks.framework.middleware.ToolContext
import org.koaks.framework.model.AgentError
import org.koaks.framework.model.Message
import org.koaks.framework.model.ModelEvent
import org.koaks.framework.policy.Recovery
import org.koaks.framework.policy.TerminationDecision
import org.koaks.framework.tool.ToolOutcome
import kotlin.time.Duration.Companion.milliseconds

/**
 * The independent, strongly-typed agent loop. NOT graph-based — it is just a
 * `while` with a model step, a tool step, and a "more tool calls?" branch.
 *
 * The critical behaviors:
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

    fun stream(initial: List<Message>): Flow<AgentEvent> = flow {
        runLoop(initial) { emitEvent(it) }
    }

    /**
     * The core loop. Emits each [AgentEvent] through [emit] (which also notifies
     * listeners) and returns the FULL final transcript — every assistant and tool
     * message accumulated, ending with the terminal assistant answer. The transcript
     * is what [runStructured] needs: the terminal answer alone is usually a summary
     * that has dropped the tool-result detail a structured schema must be filled from.
     */
    private suspend fun runLoop(
        initial: List<Message>,
        emit: suspend (AgentEvent) -> Unit,
    ): List<Message> {
        suspend fun out(event: AgentEvent) {
            agent.listeners.forEach { it.onAgentEvent(event) }
            emit(event)
        }

        // Resolve any deferred tool sources (e.g. MCP tools/list) once, in this
        // suspend context, before the first model step.
        agent.tools.resolveLazySources()

        var state = AgentState(messages = initial, activeAgentName = agent.name)
        var retries = 0

        while (true) {
            when (val decision = agent.terminationDecision(state)) {
                TerminationDecision.Continue -> {}
                is TerminationDecision.Stop -> {
                    out(AgentEvent.Terminated(state.lastAssistantOrEmpty(), state.usage, decision.reason))
                    return state.messages
                }
            }

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
                            out(AgentEvent.TextDelta(event.text))
                        }

                        is ModelEvent.ReasoningDelta ->
                            out(AgentEvent.ReasoningDelta(event.text))

                        is ModelEvent.ToolCallCompleted ->
                            out(AgentEvent.ToolCallRequested(event.call))

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
                            delay(r.delayMs.milliseconds)
                            continue
                        }
                        out(AgentEvent.Failed(error, state.usage + acc.usage())); return state.messages
                    }

                    is Recovery.Substitute -> {
                        state = state.append(r.message); continue
                    }

                    is Recovery.Propagate -> {
                        out(AgentEvent.Failed(error, state.usage + acc.usage())); return state.messages
                    }
                }
            }
            retries = 0

            val assistant = acc.assistantMessage()
            state = state.append(assistant).addUsage(acc.usage())
            out(AgentEvent.StepCompleted(state.step))

            val calls = acc.toolCalls()
            if (calls.isEmpty()) {
                out(AgentEvent.Completed(assistant, state.usage)); return state.messages
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
                out(ev)
                if (o is ToolOutcome.Failure) out(AgentEvent.Failed(o.error, state.usage))
            }
            state = state.appendToolResults(calls, outcomes)

            // returnDirectly is loop control: finish immediately, skip next model step.
            outcomes.firstOrNull { it is ToolOutcome.Success && it.returnDirectly }?.let { direct ->
                val output = (direct as ToolOutcome.Success).output
                val message = Message.assistant(output)
                state = state.append(message)
                out(AgentEvent.Completed(message, state.usage))
                return state.messages
            }
        }
    }

    suspend fun run(initial: List<Message>): AgentResult {
        val events = mutableListOf<AgentEvent>()
        runLoop(initial) { events += it }
        return resultFrom(events)
    }

    private fun resultFrom(events: List<AgentEvent>): AgentResult {
        when (val terminal = events.filterIsInstance<AgentEvent.Terminal>().lastOrNull()) {
            is AgentEvent.Completed -> return AgentResult.Completed(terminal.message, terminal.usage)
            is AgentEvent.Terminated -> return AgentResult.Terminated(terminal.message, terminal.usage, terminal.reason)
            null -> {}
        }
        val failed = events.filterIsInstance<AgentEvent.Failed>().lastOrNull()
        return AgentResult.Failed(
            error = failed?.error ?: AgentError.ModelError("agent run ended without a terminal event", retriable = false),
            usage = failed?.usage ?: org.koaks.framework.model.Usage.ZERO,
        )
    }

    /**
     * Runs to a terminal answer, then issues ONE final format-constrained request to
     * produce structured output (design: "format only on the last step"). The
     * tool loop above runs WITHOUT a json constraint so the model can call tools
     * freely; only this finalization step constrains the format, choosing native
     * jsonMode vs prompt injection from [org.koaks.framework.model.LanguageModel.capabilities].
     *
     * The finalization request is built on the FULL transcript (every assistant and
     * tool-result message), not just the terminal answer — the final answer is usually
     * a natural-language summary that has already dropped detail the schema needs, so
     * formatting from it alone would yield incomplete structured output.
     */
    suspend fun runStructured(
        initial: List<Message>,
        spec: OutputSpec,
    ): AgentResult {
        val events = mutableListOf<AgentEvent>()
        val transcript = runLoop(initial) { events += it }
        val base = resultFrom(events)
        if (base !is AgentResult.Completed) return base

        val useJsonMode = agent.model.capabilities.jsonMode
        val formatInstruction = org.koaks.framework.model.Message.user(
            buildString {
                append("Return ONLY a JSON value matching this schema for '${spec.schemaName}', with no prose or code fences:\n")
                append(spec.schema.toString())
            }
        )
        // Full conversation (history + tool results + the loop's final answer) + the format ask.
        val convo = transcript + formatInstruction
        val request = org.koaks.framework.model.ChatRequest(
            messages = convo,
            tools = emptyList(),          // no tools on the finalization step
            stream = false,
            jsonMode = useJsonMode,
        )

        val acc = TurnAccumulator()
        agent.model.generate(request).collect { acc.observe(it) }
        val text = acc.assistantMessage().text
        return AgentResult.Completed(
            message = Message.assistant(text),
            usage = base.usage + acc.usage(),
        )
    }

    /** Helper to also notify listeners on each outgoing AgentEvent (single tee point). */
    private suspend fun kotlinx.coroutines.flow.FlowCollector<AgentEvent>.emitEvent(event: AgentEvent) {
        agent.listeners.forEach { it.onAgentEvent(event) }
        emit(event)
    }
}

private fun Agent.terminationDecision(state: AgentState): TerminationDecision =
    when (val budgetDecision = runBudget.evaluate(state)) {
        TerminationDecision.Continue -> termination.evaluate(state)
        is TerminationDecision.Stop -> budgetDecision
    }

private fun Throwable.toAgentError(): AgentError = AgentError.ModelError(
    message = message ?: "model call failed",
    retriable = false,
    cause = this,
)
