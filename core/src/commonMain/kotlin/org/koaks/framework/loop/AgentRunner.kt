package org.koaks.framework.loop

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.koaks.framework.middleware.ModelCallPhase
import org.koaks.framework.middleware.StepContext
import org.koaks.framework.middleware.ToolContext
import org.koaks.framework.middleware.ToolDecision
import org.koaks.framework.model.AgentError
import org.koaks.framework.model.ChatRequest
import org.koaks.framework.model.Message
import org.koaks.framework.model.ModelEvent
import org.koaks.framework.policy.Recovery
import org.koaks.framework.policy.TerminationDecision
import org.koaks.framework.tool.ToolOutcome
import org.koaks.framework.transport.StreamIdleTimeoutException
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
internal class AgentRunner(private val agent: Agent) {

    private val logger = KotlinLogging.logger {}

    private data class LoopRun(val state: AgentState)

    fun stream(initial: List<Message>): Flow<AgentEvent> = flow {
        runLoop(initial) { emit(it) }
    }

    /**
     * The core loop. Emits each [AgentEvent] through [emit] (which also notifies
     * listeners) and returns the final loop state, including the FULL transcript —
     * every assistant and tool message accumulated, ending with the terminal assistant
     * answer. The transcript is what [runStructured] needs: the terminal answer alone
     * is usually a summary that has dropped the tool-result detail a structured schema
     * must be filled from.
     */
    private suspend fun runLoop(
        initial: List<Message>,
        emit: suspend (AgentEvent) -> Unit,
    ): LoopRun {
        suspend fun out(event: AgentEvent) {
            agent.listeners.forEach { it.onAgentEvent(event) }
            emit(event)
        }

        var state = AgentState(messages = initial, activeAgentName = agent.name)
        var retries = 0

        while (true) {
            when (val decision = agent.terminationDecision(state)) {
                TerminationDecision.Continue -> {}
                is TerminationDecision.Stop -> {
                    out(AgentEvent.Terminated(state.lastAssistantOrEmpty(), state.usage, decision.reason))
                    return LoopRun(state)
                }
            }

            val acc = TurnAccumulator()
            agent.listeners.forEach { it.onStep(state) }

            // model step — hooks may transform the request and lazily wrap the flow.
            var emittedText = false
            try {
                // Build inside try so a throwing onModelRequest/onModelStream hook is mapped
                // to an AgentError and run through the same error policy as a model failure.
                val source = modelSource(state, agent.toRequest(state), ModelCallPhase.Normal)
                source.collect { event ->
                    acc.observe(event)
                    agent.listeners.forEach { it.onModelEvent(event) }
                    when (event) {
                        is ModelEvent.TextDelta -> {
                            logger.debug { "AgentRunner: emitting text delta: ${event.text}" }
                            emittedText = true
                            out(AgentEvent.TextDelta(event.text))
                        }

                        is ModelEvent.ReasoningDelta -> {
                            logger.debug { "AgentRunner: emitting reasoning delta: ${event.text}" }
                            out(AgentEvent.ReasoningDelta(event.text))
                        }


                        is ModelEvent.ToolCallCompleted -> {
                            logger.debug { "AgentRunner: emitting tool result: ${event.call}" }
                            out(AgentEvent.ToolCallRequested(event.call))
                        }


                        is ModelEvent.Failed -> {
                            logger.error { "AgentRunner: emitting model failure: ${event.error}" }
                            throw ModelFailure(event.error)
                        }

                        else -> {
                            // ignore other model events (e.g. usage, metadata)
                            logger.debug { "AgentRunner: ignoring model event: $event"}
                        }
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
                        out(AgentEvent.Failed(error, state.usage + acc.usage())); return LoopRun(state)
                    }

                    is Recovery.Substitute -> {
                        state = state.append(r.message); continue
                    }

                    is Recovery.Propagate -> {
                        out(AgentEvent.Failed(error, state.usage + acc.usage())); return LoopRun(state)
                    }
                }
            }
            retries = 0

            val assistant = acc.assistantMessage()
            state = state.append(assistant).addUsage(acc.usage())
            out(AgentEvent.StepCompleted(state.step))

            val calls = acc.toolCalls()
            if (calls.isEmpty()) {
                out(AgentEvent.Completed(assistant, state.usage)); return LoopRun(state)
            }

            // tool step — parallel; failures travel the explicit channel (isError tool message).
            // Each tool runs as its own execution branch so a runtime can track per-branch
            // activity (a tool that spawns and awaits a child marks only its own branch
            // waiting). Branches are forked synchronously, before the root awaits them.
            val exec = currentCoroutineContext()[AgentExecutionContext]
            val outcomes: List<ToolOutcome> = coroutineScope {
                // Fork all branches first, on this (root) coroutine, before awaiting any —
                // so the instance never looks fully-waiting mid-registration.
                val branches = if (exec != null) calls.map { exec.forkBranch() } else null
                val deferreds = calls.mapIndexed { i, call ->
                    val branch = branches?.get(i)
                    async {
                        val body: suspend () -> ToolOutcome = {
                            var current = call
                            var denied: ToolOutcome? = null
                            // Count of hooks whose onToolCall ran. Only these unwind via
                            // onToolResult, in reverse (onion): a hook skipped past a Deny
                            // never entered, so its result side must not run either.
                            var entered = 0

                            try {
                                hookLoop@ for (hook in agent.hooks) {
                                    entered++
                                    when (val decision = hook.onToolCall(ToolContext(current, state))) {
                                        ToolDecision.Proceed -> {}
                                        is ToolDecision.ProceedWith -> {
                                            current = decision.call.copy(id = call.id)
                                        }

                                        is ToolDecision.Deny -> {
                                            denied = ToolOutcome.Failure(
                                                AgentError.ToolError(
                                                    toolName = current.name,
                                                    message = decision.reason,
                                                    retriable = false,
                                                )
                                            )
                                            break@hookLoop
                                        }
                                    }
                                }

                                var outcome = denied ?: agent.tools.call(current.name, current.arguments) {
                                    exec?.markSideEffect()
                                }
                                for (hook in agent.hooks.take(entered).asReversed()) {
                                    outcome = hook.onToolResult(ToolContext(current, state), outcome)
                                }
                                outcome
                            } catch (c: CancellationException) {
                                throw c
                            } catch (t: Throwable) {
                                // A hook (onToolCall/onToolResult) threw — route it through the
                                // explicit tool failure channel instead of crashing the run.
                                ToolOutcome.Failure(
                                    AgentError.ToolError(
                                        toolName = current.name,
                                        message = t.message ?: "tool hook failed",
                                        retriable = false,
                                        cause = t,
                                    )
                                )
                            }
                        }
                        if (branch != null) branch.run { body() } else body()
                    }
                }
                // The root branch is waiting while its tool branches run; a runtime keeps
                // the slot as long as any tool branch is still runnable.
                if (exec != null) exec.waiting { deferreds.awaitAll() } else deferreds.awaitAll()
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
                return LoopRun(state)
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
            error = failed?.error ?: AgentError.ModelError(
                "agent run ended without a terminal event",
                retriable = false
            ),
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
    ): AgentResult = runStructured(initial, spec) {}

    /** Runtime-facing structured execution with observable intermediate steps. */
    fun streamStructured(initial: List<Message>, spec: OutputSpec): Flow<AgentEvent> = flow {
        runStructured(initial, spec) { emit(it) }
    }

    private suspend fun runStructured(
        initial: List<Message>,
        spec: OutputSpec,
        emit: suspend (AgentEvent) -> Unit,
    ): AgentResult {
        val events = mutableListOf<AgentEvent>()
        val loop = runLoop(initial) { event ->
            events += event
            // The successful base answer is an internal draft. Runtime still observes
            // tool calls/results and StepCompleted for quotas/metrics, but Memory should
            // retain only the final structured answer, not this draft's text.
            when (event) {
                is AgentEvent.TextDelta,
                is AgentEvent.ReasoningDelta,
                is AgentEvent.Completed,
                -> Unit
                else -> emit(event)
            }
        }
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
        val convo = loop.state.messages + formatInstruction
        val finalizationState = loop.state.copy(messages = convo)
        val request = org.koaks.framework.model.ChatRequest(
            messages = convo,
            tools = emptyList(),          // no tools on the finalization step
            // MUST stream: the transport is SSE-only, so a non-streaming response
            // (a plain JSON body with no `data:` lines) is silently dropped, yielding
            // empty text. jsonMode still applies — it constrains the format, not the framing.
            stream = true,
            jsonMode = useJsonMode,
        )

        val acc = TurnAccumulator()
        try {
            modelSource(finalizationState, request, ModelCallPhase.StructuredFinalization)
                .collect { event ->
                    acc.observe(event)
                    agent.listeners.forEach { it.onModelEvent(event) }
                    when (event) {
                        is ModelEvent.TextDelta -> emitStructuredEvent(AgentEvent.TextDelta(event.text), emit)
                        is ModelEvent.ReasoningDelta -> emitStructuredEvent(AgentEvent.ReasoningDelta(event.text), emit)
                        is ModelEvent.Failed -> throw ModelFailure(event.error)
                        else -> Unit
                    }
                }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            // A finalization hook (or the model) failed — surface it on the unified
            // result channel instead of throwing raw out of run<T>.
            val error = if (t is ModelFailure) t.error else t.toAgentError()
            val usage = base.usage + acc.usage()
            emitStructuredEvent(AgentEvent.Failed(error, usage), emit)
            return AgentResult.Failed(error, usage)
        }
        val text = acc.assistantMessage().text
        val usage = base.usage + acc.usage()
        emitStructuredEvent(AgentEvent.StepCompleted(loop.state.step + 1), emit)
        val completed = AgentEvent.Completed(Message.assistant(text), usage)
        emitStructuredEvent(completed, emit)
        return AgentResult.Completed(
            message = Message.assistant(text),
            usage = usage,
        )
    }

    /** Finalization events do not pass through [runLoop], so notify listeners here. */
    private suspend fun emitStructuredEvent(event: AgentEvent, emit: suspend (AgentEvent) -> Unit) {
        agent.listeners.forEach { it.onAgentEvent(event) }
        emit.invoke(event)
    }

    private suspend fun modelSource(
        state: AgentState,
        request: ChatRequest,
        phase: ModelCallPhase,
    ): Flow<ModelEvent> {
        var currentRequest = request
        for (hook in agent.hooks) {
            currentRequest = hook.onModelRequest(StepContext(state, currentRequest, phase))
        }

        val ctx = StepContext(state, currentRequest, phase)
        var source = agent.model.generate(currentRequest)
        for (hook in agent.hooks.asReversed()) {
            source = hook.onModelStream(ctx, source)
        }
        return source
    }
}

private fun Agent.terminationDecision(state: AgentState): TerminationDecision =
    when (val budgetDecision = runBudget.evaluate(state)) {
        TerminationDecision.Continue -> termination.evaluate(state)
        is TerminationDecision.Stop -> budgetDecision
    }

private fun Throwable.toAgentError(): AgentError = when (this) {
    is StreamIdleTimeoutException -> AgentError.Timeout(
        stage = "model response stream idle",
        elapsedMs = idleTimeoutMs,
    )

    else -> AgentError.ModelError(
        message = message ?: "model call failed",
        retriable = false,
        cause = this,
    )
}
