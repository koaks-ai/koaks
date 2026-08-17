package org.koaks.framework.loop

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koaks.framework.memory.InterruptReason
import org.koaks.framework.middleware.ModelCallPhase
import org.koaks.framework.middleware.StepContext
import org.koaks.framework.middleware.ToolContext
import org.koaks.framework.middleware.ToolDecision
import org.koaks.framework.model.AgentError
import org.koaks.framework.model.EventDetail
import org.koaks.framework.model.ModelEvent
import org.koaks.framework.model.ModelItem
import org.koaks.framework.model.ModelRequest
import org.koaks.framework.model.ModelResponse
import org.koaks.framework.model.OutputFormat
import org.koaks.framework.model.Support
import org.koaks.framework.model.toDispatchCall
import org.koaks.framework.model.newIdempotencyKey
import org.koaks.framework.policy.Recovery
import org.koaks.framework.policy.TerminationDecision
import org.koaks.framework.tool.ToolOutcome
import org.koaks.framework.transport.StreamIdleTimeoutException
import kotlin.time.Duration.Companion.milliseconds

internal class AgentRunner(private val agent: Agent) {

    private val logger = KotlinLogging.logger {}

    internal data class LoopRun(val state: AgentState)

    fun stream(
        initial: List<ModelItem>,
        instructions: String?,
        turn: TurnBuilder,
        checkpoint: org.koaks.framework.model.ProviderCheckpoint? = null,
        eventDetail: EventDetail = EventDetail.SEMANTIC,
    ): Flow<AgentEvent> = channelFlow {
        runLoop(initial, instructions, turn, checkpoint, eventDetail) { send(it) }
    }

    private suspend fun runLoop(
        initial: List<ModelItem>,
        instructions: String?,
        turn: TurnBuilder,
        checkpoint: org.koaks.framework.model.ProviderCheckpoint?,
        eventDetail: EventDetail,
        emit: suspend (AgentEvent) -> Unit,
    ): LoopRun {
        val outputMutex = Mutex()
        suspend fun out(event: AgentEvent) {
            outputMutex.withLock {
                agent.listeners.forEach { it.onAgentEvent(event) }
                emit(event)
            }
        }

        var state = AgentState(items = initial, instructions = instructions, checkpoint = checkpoint, activeAgentName = agent.name)
        var retries = 0
        var stepKey = newIdempotencyKey()

        while (true) {
            when (val decision = agent.terminationDecision(state)) {
                TerminationDecision.Continue -> {}
                is TerminationDecision.Stop -> {
                    out(AgentEvent.Terminated(state.lastAssistantOrEmpty(), state.usage, decision.reason))
                    return LoopRun(state)
                }
            }

            agent.listeners.forEach { it.onStep(state) }

            var emittedText = false
            var terminalResponse: ModelResponse? = null
            try {
                val source = modelSource(
                    state,
                    agent.toRequest(state, stepKey, OutputFormat.Text, eventDetail),
                    ModelCallPhase.Normal,
                )
                source.collect { event ->
                    turn.observe(event)
                    agent.listeners.forEach { it.onModelEvent(event) }
                    when (event) {
                        is ModelEvent.TextDelta -> {
                            emittedText = true
                            out(AgentEvent.TextDelta(event.text, event.itemRef))
                        }
                        is ModelEvent.ReasoningDelta -> out(AgentEvent.ReasoningDelta(event.text, event.itemRef, event.kind))
                        is ModelEvent.ToolCallCompleted -> out(AgentEvent.ToolCallRequested(event.call))
                        is ModelEvent.Finished -> {
                            if (eventDetail == EventDetail.LOSSLESS) {
                                out(AgentEvent.Model(event, state.step, ModelCallPhase.Normal))
                            }
                            val response = event.response
                            terminalResponse = response
                            state = state.addUsage(response.usage).withCheckpoint(response.checkpoint)
                            if (response is ModelResponse.Failed) {
                                throw ModelFailure(response.error)
                            }
                        }
                        else -> {
                            if (eventDetail == EventDetail.LOSSLESS) {
                                out(AgentEvent.Model(event, state.step, ModelCallPhase.Normal))
                            } else {
                                logger.debug { "AgentRunner: ignoring model event: $event" }
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                val error: AgentError = when (t) {
                    is ModelFailure -> t.error
                    is CancellationException -> throw t
                    else -> t.toAgentError()
                }
                val recovery = agent.suspendErrorPolicy?.decide(error, state)
                    ?: agent.errorPolicy.decide(error, state)
                when (val r = recovery) {
                    is Recovery.Retry -> {
                        if (!emittedText && retries < r.maxRetries) {
                            retries++
                            delay(r.delayMs.milliseconds)
                            continue
                        }
                        out(AgentEvent.Failed(error, state.usage))
                        return LoopRun(state)
                    }
                    is Recovery.Substitute -> {
                        state = state.append(r.message)
                        turn.append(r.message)
                        continue
                    }
                    is Recovery.Propagate -> {
                        out(AgentEvent.Failed(error, state.usage))
                        return LoopRun(state)
                    }
                }
            }
            retries = 0
            stepKey = newIdempotencyKey()

            val response = checkNotNull(terminalResponse) { "model stream ended without a terminal response" }
            val modelOutput = turn.reconciledOutput(response)
            state = state.completeModelStep(modelOutput)
            val assistant = modelOutput.filterIsInstance<ModelItem.Message>()
                .lastOrNull { it.role == org.koaks.framework.model.Role.ASSISTANT }
                ?: turn.assistantMessage()
            out(AgentEvent.StepCompleted(state.step))

            if (response is ModelResponse.Incomplete) {
                out(AgentEvent.Incomplete(assistant, state.usage, response.reason))
                return LoopRun(state)
            }

            val calls = modelOutput.filterIsInstance<ModelItem.ToolCall>().map { it.toDispatchCall() }
            val actionResults = dispatchClientActions(modelOutput)
            if (actionResults.isNotEmpty()) {
                actionResults.forEach { turn.append(it) }
                state = state.appendAll(actionResults)
            }
            if (calls.isEmpty() && actionResults.isEmpty()) {
                out(AgentEvent.Completed(assistant, state.usage))
                return LoopRun(state)
            }
            if (calls.isEmpty()) {
                continue
            }

            val exec = currentCoroutineContext()[AgentExecutionContext]
            val outcomes: List<ToolOutcome> = coroutineScope {
                val branches = if (exec != null) calls.map { exec.forkBranch() } else null
                val deferreds = calls.mapIndexed { i, call ->
                    val branch = branches?.get(i)
                    async {
                        val body: suspend () -> ToolOutcome = {
                            var current = call
                            var denied: ToolOutcome? = null
                            var entered = 0
                            try {
                                hookLoop@ for (hook in agent.hooks) {
                                    entered++
                                    when (val decision = hook.onToolCall(ToolContext(current, state, exec?.identity))) {
                                        ToolDecision.Proceed -> {}
                                        is ToolDecision.ProceedWith -> current = decision.call.copy(id = call.id)
                                        is ToolDecision.Deny -> {
                                            denied = ToolOutcome.Failure(
                                                AgentError.ToolError(
                                                    toolName = current.name,
                                                    message = decision.reason,
                                                    retriable = false,
                                                ),
                                            )
                                            break@hookLoop
                                        }
                                    }
                                }
                                var outcome = denied ?: agent.tools.call(
                                    call = current,
                                    execution = exec?.identity,
                                    reportProgress = { progress ->
                                        out(AgentEvent.ToolProgress(current.id, progress))
                                    },
                                    onSideEffect = { exec?.markSideEffect() },
                                )
                                for (hook in agent.hooks.take(entered).asReversed()) {
                                    outcome = hook.onToolResult(ToolContext(current, state, exec?.identity), outcome)
                                }
                                outcome
                            } catch (c: CancellationException) {
                                throw c
                            } catch (t: Throwable) {
                                ToolOutcome.Failure(
                                    AgentError.ToolError(
                                        toolName = current.name,
                                        message = t.message ?: "tool hook failed",
                                        retriable = false,
                                        cause = t,
                                    ),
                                )
                            }
                        }
                        if (branch != null) branch.run { body() } else body()
                    }
                }
                if (exec != null) exec.waiting { deferreds.awaitAll() } else deferreds.awaitAll()
            }
            outcomes.forEachIndexed { i, o ->
                val ev = o.toEvent(calls[i].id)
                out(ev)
                if (o is ToolOutcome.Failure) out(AgentEvent.Failed(o.error, state.usage))
            }
            state = state.appendToolResults(calls, outcomes)
            state.items.takeLast(calls.size).forEach { turn.append(it) }

            outcomes.firstOrNull { it is ToolOutcome.Success && it.returnDirectly }?.let { direct ->
                val output = (direct as ToolOutcome.Success).output
                val message = ModelItem.assistant(output)
                state = state.append(message)
                turn.append(message)
                out(AgentEvent.Completed(message, state.usage))
                return LoopRun(state)
            }
        }
    }

    suspend fun run(
        initial: List<ModelItem>,
        instructions: String?,
        turn: TurnBuilder,
        checkpoint: org.koaks.framework.model.ProviderCheckpoint? = null,
    ): AgentResult {
        val events = mutableListOf<AgentEvent>()
        runLoop(initial, instructions, turn, checkpoint, EventDetail.SEMANTIC) { events += it }
        return resultFrom(events)
    }

    private fun resultFrom(events: List<AgentEvent>): AgentResult {
        when (val terminal = events.filterIsInstance<AgentEvent.Terminal>().lastOrNull()) {
            is AgentEvent.Completed -> return AgentResult.Completed(terminal.message, terminal.usage)
            is AgentEvent.Incomplete -> return AgentResult.Incomplete(terminal.message, terminal.usage, terminal.reason)
            is AgentEvent.Terminated -> return AgentResult.Terminated(terminal.message, terminal.usage, terminal.reason)
            null -> {}
        }
        val failed = events.filterIsInstance<AgentEvent.Failed>().lastOrNull()
        return AgentResult.Failed(
            error = failed?.error ?: AgentError.ModelError(
                "agent run ended without a terminal event",
                retriable = false,
            ),
            usage = failed?.usage ?: org.koaks.framework.model.Usage.ZERO,
        )
    }

    fun streamStructured(
        initial: List<ModelItem>,
        instructions: String?,
        spec: OutputSpec,
        turn: TurnBuilder,
        checkpoint: org.koaks.framework.model.ProviderCheckpoint? = null,
        eventDetail: EventDetail = EventDetail.SEMANTIC,
    ): Flow<AgentEvent> = flow {
        runStructured(initial, instructions, spec, turn, checkpoint, eventDetail) { emit(it) }
    }

    private suspend fun runStructured(
        initial: List<ModelItem>,
        instructions: String?,
        spec: OutputSpec,
        turn: TurnBuilder,
        checkpoint: org.koaks.framework.model.ProviderCheckpoint?,
        eventDetail: EventDetail,
        emit: suspend (AgentEvent) -> Unit,
    ): AgentResult {
        val events = mutableListOf<AgentEvent>()
        val loop = runLoop(initial, instructions, turn, checkpoint, eventDetail) { event ->
            events += event
            when (event) {
                is AgentEvent.TextDelta,
                is AgentEvent.ReasoningDelta,
                is AgentEvent.Completed,
                is AgentEvent.Incomplete,
                -> Unit
                else -> emit(event)
            }
        }
        val base = resultFrom(events)
        if (base !is AgentResult.Completed) return base

        val format = structuredFormat(spec)
        val formatInstruction = if (format is OutputFormat.JsonSchema) {
            null
        } else {
            ModelItem.user(
                "Return ONLY a JSON value matching this schema for '${spec.schemaName}', with no prose or code fences:\n${spec.schema}",
            )
        }
        val convo = loop.state.items + listOfNotNull(formatInstruction)
        formatInstruction?.let { turn.append(it) }
        val finalizationState = loop.state.copy(items = convo)
        val request = agent.toRequest(finalizationState, newIdempotencyKey(), format, eventDetail).copy(tools = emptyList())

        var finalizationResponse: ModelResponse? = null
        try {
            modelSource(finalizationState, request, ModelCallPhase.StructuredFinalization)
                .collect { event ->
                    turn.observe(event)
                    agent.listeners.forEach { it.onModelEvent(event) }
                    when (event) {
                        is ModelEvent.TextDelta -> emitStructuredEvent(AgentEvent.TextDelta(event.text, event.itemRef), emit)
                        is ModelEvent.ReasoningDelta -> emitStructuredEvent(
                            AgentEvent.ReasoningDelta(event.text, event.itemRef, event.kind),
                            emit,
                        )
                        is ModelEvent.Finished -> {
                            if (eventDetail == EventDetail.LOSSLESS) {
                                emitStructuredEvent(
                                    AgentEvent.Model(event, loop.state.step, ModelCallPhase.StructuredFinalization),
                                    emit,
                                )
                            }
                            finalizationResponse = event.response
                            if (event.response is ModelResponse.Failed) {
                                throw ModelFailure(event.response.error)
                            }
                        }
                        else -> if (eventDetail == EventDetail.LOSSLESS) {
                            emitStructuredEvent(
                                AgentEvent.Model(event, loop.state.step, ModelCallPhase.StructuredFinalization),
                                emit,
                            )
                        }
                    }
                }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            val error = if (t is ModelFailure) t.error else t.toAgentError()
            val usage = base.usage + (finalizationResponse?.usage ?: org.koaks.framework.model.Usage.ZERO)
            emitStructuredEvent(AgentEvent.Failed(error, usage), emit)
            return AgentResult.Failed(error, usage)
        }
        val finalResponse = checkNotNull(finalizationResponse) { "structured model stream ended without a terminal response" }
        val finalAssistant = turn.reconciledOutput(finalResponse).filterIsInstance<ModelItem.Message>()
            .lastOrNull { it.role == org.koaks.framework.model.Role.ASSISTANT }
            ?: turn.assistantMessage()
        val usage = base.usage + finalResponse.usage
        emitStructuredEvent(AgentEvent.StepCompleted(loop.state.step + 1), emit)
        if (finalResponse is ModelResponse.Incomplete) {
            emitStructuredEvent(AgentEvent.Incomplete(finalAssistant, usage, finalResponse.reason), emit)
            return AgentResult.Incomplete(finalAssistant, usage, finalResponse.reason)
        }
        val message = ModelItem.assistant(finalAssistant.text)
        turn.collapseToFinalAssistant(message, dropRefs = setOfNotNull(formatInstruction?.ref))
        emitStructuredEvent(AgentEvent.Completed(message, usage), emit)
        return AgentResult.Completed(message, usage)
    }

    private fun structuredFormat(spec: OutputSpec): OutputFormat {
        val caps = agent.model.capabilities
        return when {
            caps.jsonSchema != Support.Unsupported ->
                OutputFormat.JsonSchema(spec.schemaName, spec.schema)
            caps.jsonObject != Support.Unsupported -> OutputFormat.JsonObject
            else -> OutputFormat.Text
        }
    }

    private suspend fun emitStructuredEvent(event: AgentEvent, emit: suspend (AgentEvent) -> Unit) {
        agent.listeners.forEach { it.onAgentEvent(event) }
        emit.invoke(event)
    }

    private suspend fun modelSource(
        state: AgentState,
        request: ModelRequest,
        phase: ModelCallPhase,
    ): Flow<ModelEvent> {
        var currentRequest = request
        for (hook in agent.hooks) {
            currentRequest = hook.onModelRequest(StepContext(state, currentRequest, phase))
        }
        val ctx = StepContext(state, currentRequest, phase)
        var source = agent.model.stream(currentRequest)
        for (hook in agent.hooks.asReversed()) {
            source = hook.onModelStream(ctx, source)
        }
        return source
    }

    private suspend fun dispatchClientActions(output: List<ModelItem>): List<ModelItem> {
        if (agent.clientActionHandlers.isEmpty()) return emptyList()
        val pending = output.filterIsInstance<ModelItem.ProviderItem>()
            .filter { it.kind in CLIENT_ACTION_KINDS }
        if (pending.isEmpty()) return emptyList()
        val results = mutableListOf<ModelItem>()
        for (item in pending) {
            for (handler in agent.clientActionHandlers) {
                val handled = handler.handle(item) ?: continue
                results += handled
                break
            }
        }
        return results
    }
}

private val CLIENT_ACTION_KINDS = setOf(
    "computer_call",
    "mcp_approval_request",
    "local_shell_call",
    "custom_tool_call",
)

private suspend fun Agent.terminationDecision(state: AgentState): TerminationDecision =
    when (val budgetDecision = runBudget.evaluate(state)) {
        TerminationDecision.Continue -> suspendTermination?.evaluate(state) ?: termination.evaluate(state)
        is TerminationDecision.Stop -> budgetDecision
    }

internal fun Throwable.toAgentError(): AgentError = when (this) {
    is org.koaks.framework.model.AgentFrameworkException -> error
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
