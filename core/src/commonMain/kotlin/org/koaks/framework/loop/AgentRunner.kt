package org.koaks.framework.loop

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koaks.framework.memory.ThreadMemory
import org.koaks.framework.memory.forOnlineUse
import org.koaks.framework.middleware.ModelCallPhase
import org.koaks.framework.middleware.StepContext
import org.koaks.framework.middleware.ToolContext
import org.koaks.framework.middleware.ToolDecision
import org.koaks.framework.middleware.StepHookContext
import org.koaks.framework.middleware.StepMemory
import org.koaks.framework.middleware.StepDecision
import org.koaks.framework.middleware.StepPhase
import org.koaks.framework.policy.StepPolicyEvaluator
import org.koaks.framework.policy.TerminationReason
import org.koaks.framework.model.takeIfValidFor
import org.koaks.framework.model.AgentError
import org.koaks.framework.model.EventDetail
import org.koaks.framework.model.ModelEvent
import org.koaks.framework.model.ModelItem
import org.koaks.framework.model.ModelRequest
import org.koaks.framework.model.ModelResponse
import org.koaks.framework.model.OutputFormat
import org.koaks.framework.model.Role
import org.koaks.framework.model.Support
import org.koaks.framework.model.toDispatchCall
import org.koaks.framework.model.newIdempotencyKey
import org.koaks.framework.policy.Recovery
import org.koaks.framework.policy.TerminationDecision
import org.koaks.framework.tool.ToolOutcome
import org.koaks.framework.transport.StreamIdleTimeoutException
import kotlin.time.Duration.Companion.milliseconds

internal class AgentRunner(private val agent: Agent) {
    private val policies = StepPolicyEvaluator(agent)

    private suspend fun stepHooks(state: AgentState, memory: ThreadMemory, stage: StepPhase,
        phase: ModelCallPhase = ModelCallPhase.Normal, retry: Int = 0,
    ) {
        val coroutine = currentCoroutineContext()
        val services = coroutine[RunStepServices]
        services?.pause?.invoke(null)
        for (hook in services?.hooks.orEmpty() + agent.hooks) {
            val access = StepMemory(memory)
            val decision = try {
                hook.onStep(StepHookContext(state, stage, phase, access, coroutine[AgentExecutionContext]?.identity, retry))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (hook in agent.hooks) throw StepHookFailed(failure)
                throw failure
            } finally { access.close() }
            when (decision) {
                StepDecision.Continue -> Unit
                is StepDecision.Stop -> throw StepStopped(decision.reason)
                is StepDecision.Suspend -> requireNotNull(services) { "step suspension requires runtime execution" }.pause(decision.reason)
            }
        }
    }

    private fun enforce(decision: TerminationDecision) {
        if (decision is TerminationDecision.Stop) throw StepStopped(decision.reason)
    }

    private suspend fun finishIfEmpty(): Boolean = currentCoroutineContext()[RunStepServices]?.finishIfEmpty?.invoke() ?: true

    private val logger = KotlinLogging.logger {}

    internal data class LoopRun(val state: AgentState, val items: List<ModelItem>)

    fun stream(
        memory: ThreadMemory,
        instructions: String?,
        eventDetail: EventDetail = EventDetail.SEMANTIC,
        context: List<ModelItem> = emptyList(),
    ): Flow<AgentEvent> = channelFlow {
        runLoop(memory, instructions, eventDetail, context) { send(it) }
    }

    private suspend fun runLoop(
        memory: ThreadMemory,
        instructions: String?,
        eventDetail: EventDetail,
        context: List<ModelItem> = emptyList(),
        initialState: AgentState? = null,
        closeOnCompletion: Boolean = true,
        emit: suspend (AgentEvent) -> Unit,
    ): LoopRun {
        val outputMutex = Mutex()
        suspend fun out(event: AgentEvent) {
            outputMutex.withLock {
                agent.listeners.forEach { it.onAgentEvent(event) }
                emit(event)
            }
        }

        val builder = TurnBuilder()
        var query: ModelItem? = null
        val persistenceMutex = Mutex()
        var state = initialState ?: AgentState(instructions = instructions, activeAgentName = agent.name)
        var retries = 0
        var stepKey = newIdempotencyKey()
        var previousRequest: ModelRequest? = null

        fun identifyRequest(request: ModelRequest): ModelRequest {
            val previous = previousRequest
            val next = if (previous != null && previous.idempotencyKey == request.idempotencyKey &&
                previous != request) request.copy(idempotencyKey = newIdempotencyKey()) else request
            stepKey = next.idempotencyKey
            previousRequest = next
            return next
        }

        suspend fun snapshot(): List<ModelItem> =
            memory.load(listOfNotNull(query)).forOnlineUse().transcript

        suspend fun persist(item: ModelItem) {
            withContext(NonCancellable) {
                persistenceMutex.withLock {
                    memory.append(item)
                    query = item
                }
            }
        }

        suspend fun persistCompleted() {
            builder.drainCompletedItems().forEach { persist(it) }
        }

        suspend fun persistPartial() {
            builder.flushPartial()
            persistCompleted()
        }

        try {
            while (true) {
                var emittedText = false
                var terminalResponse: ModelResponse? = null
                try {
                    enforce(policies.beforeModel(state))
                    stepHooks(state, memory, StepPhase.BeforeModelRequest, retry = retries)
                    val items = snapshot()
                    agent.listeners.forEach { it.onStep(state) }
                    val requestItems = agent.applyPrefill(context + items)
                    val source = modelSource(
                        state,
                        agent.toRequest(state, requestItems, stepKey, OutputFormat.Text, eventDetail),
                        ModelCallPhase.Normal,
                        ::identifyRequest,
                    )
                    builder.reset()
                    source.collect { event ->
                        builder.observe(event)
                        persistCompleted()
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
                                currentCoroutineContext()[RunStepServices]?.observeUsage?.invoke(state.usage)
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
                    checkNotNull(terminalResponse) { "model stream ended without a terminal response" }
                } catch (stopped: StepStopped) {
                    throw stopped
                } catch (failure: StepHookFailed) {
                    throw failure
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
                            persistPartial()
                            out(AgentEvent.Failed(error, state.usage))
                            return LoopRun(state, snapshot())
                        }
                        is Recovery.Substitute -> {
                            persistPartial()
                            persist(r.message)
                            continue
                        }
                        is Recovery.Propagate -> {
                            persistPartial()
                            out(AgentEvent.Failed(error, state.usage))
                            return LoopRun(state, snapshot())
                        }
                    }
                }
                retries = 0
                stepKey = newIdempotencyKey()

                val response = checkNotNull(terminalResponse) { "model stream ended without a terminal response" }
                val modelOutput = builder.reconciledOutput(response)
                state = state.advanceModelStep()
                val assistant = modelOutput.filterIsInstance<ModelItem.Message>()
                    .lastOrNull { it.role == Role.ASSISTANT }
                    ?: builder.assistantMessage()
                out(AgentEvent.StepCompleted(state.step))

                stepHooks(state, memory, StepPhase.AfterModelResponse)
                enforce(policies.afterModel(state))

                val calls = modelOutput.filterIsInstance<ModelItem.ToolCall>().map { it.toDispatchCall() }
                val actionResults = dispatchClientActions(modelOutput)

                actionResults.forEach { persist(it) }
                builder.reset()

                if (response is ModelResponse.Incomplete) {
                    stepHooks(state, memory, StepPhase.AfterStep)
                    out(AgentEvent.Incomplete(assistant, state.usage, response.reason))
                    return LoopRun(state, snapshot())
                }

                if (calls.isEmpty() && actionResults.isEmpty()) {
                    stepHooks(state, memory, StepPhase.AfterStep)
                    if (closeOnCompletion && !finishIfEmpty()) continue
                    out(AgentEvent.Completed(assistant, state.usage))
                    return LoopRun(state, snapshot())
                }
                if (calls.isEmpty()) {
                    stepHooks(state, memory, StepPhase.AfterStep)
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
                            val complete: suspend () -> ToolOutcome = {
                                val outcome = body()
                                val result = when (outcome) {
                                    is ToolOutcome.Success -> ModelItem.ToolResult(callRef = call.ref, output = outcome.output)
                                    is ToolOutcome.Failure -> ModelItem.ToolResult(callRef = call.ref, output = outcome.error.message, isError = true)
                                }
                                persist(result)
                                out(outcome.toEvent(call.id))
                                if (outcome is ToolOutcome.Failure) out(AgentEvent.Failed(outcome.error, state.usage))
                                outcome
                            }
                            if (branch != null) branch.run { complete() } else complete()
                        }
                    }
                    if (exec != null) exec.waiting { deferreds.awaitAll() } else deferreds.awaitAll()
                }
                outcomes.firstOrNull { it is ToolOutcome.Success && it.returnDirectly }?.let { direct ->
                    val output = (direct as ToolOutcome.Success).output
                    val message = ModelItem.assistant(output)
                    persist(message)
                    stepHooks(state, memory, StepPhase.AfterStep)
                    if (closeOnCompletion && !finishIfEmpty()) return@let
                    out(AgentEvent.Completed(message, state.usage))
                    return LoopRun(state, snapshot())
                }
                if (outcomes.none { it is ToolOutcome.Success && it.returnDirectly }) {
                    stepHooks(state, memory, StepPhase.AfterStep)
                }
            }
        } catch (stopped: StepStopped) {
            val items = snapshot()
            out(AgentEvent.Terminated(items.lastAssistantOrEmpty(), state.usage, stopped.reason))
            return LoopRun(state, items)
        } catch (failure: StepHookFailed) {
            out(AgentEvent.Failed(failure.cause!!.toAgentError(), state.usage))
            return LoopRun(state, snapshot())
        } catch (cancelled: CancellationException) {
            persistPartial()
            withContext(NonCancellable) {
                builder.lastResponseId()?.let { id -> runCatching { agent.model.abandon(id) } }
            }
            throw cancelled
        }
    }

    suspend fun run(
        memory: ThreadMemory,
        instructions: String?,
    ): AgentResult {
        val events = mutableListOf<AgentEvent>()
        runLoop(memory, instructions, EventDetail.SEMANTIC) { events += it }
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
        memory: ThreadMemory,
        instructions: String?,
        spec: OutputSpec,
        eventDetail: EventDetail = EventDetail.SEMANTIC,
        context: List<ModelItem> = emptyList(),
    ): Flow<AgentEvent> = flow {
        runStructured(memory, instructions, spec, eventDetail, context) { emit(it) }
    }

    private suspend fun runStructured(
        memory: ThreadMemory,
        instructions: String?,
        spec: OutputSpec,
        eventDetail: EventDetail,
        context: List<ModelItem>,
        emit: suspend (AgentEvent) -> Unit,
    ): AgentResult {
        var accumulated: AgentState? = null
        while (true) {
        val events = mutableListOf<AgentEvent>()
        val loop = runLoop(memory, instructions, eventDetail, context = context, initialState = accumulated, closeOnCompletion = false) { event ->
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
        if (base !is AgentResult.Completed) {
            events.filterIsInstance<AgentEvent.Incomplete>().lastOrNull()?.let { emit(it) }
            return base
        }

        val format = structuredFormat(spec)
        val formatInstruction = if (format is OutputFormat.JsonSchema) {
            null
        } else {
            ModelItem.user(
                "Return ONLY a JSON value matching this schema for '${spec.schemaName}', with no prose or code fences:\n${spec.schema}",
            )
        }
        var finalizationState = loop.state
        val builder = TurnBuilder()
        val buffered = mutableListOf<AgentEvent>()
        var finalizationResponse: ModelResponse? = null
        try {
            enforce(policies.beforeModel(finalizationState))
            stepHooks(finalizationState, memory, StepPhase.BeforeModelRequest, ModelCallPhase.StructuredFinalization)
            val loaded = memory.load(emptyList()).forOnlineUse().transcript
            val convo = context + loaded + listOfNotNull(formatInstruction)
            val request = agent.toRequest(finalizationState, convo, newIdempotencyKey(), format, eventDetail).copy(tools = emptyList())
            modelSource(finalizationState, request, ModelCallPhase.StructuredFinalization)
                .collect { event ->
                    builder.observe(event)
                    withContext(NonCancellable) {
                        builder.drainCompletedItems().forEach { memory.append(it) }
                    }
                    agent.listeners.forEach { it.onModelEvent(event) }
                    when (event) {
                        is ModelEvent.TextDelta -> buffered.add(AgentEvent.TextDelta(event.text, event.itemRef))
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
                            finalizationState = finalizationState.addUsage(event.response.usage).withCheckpoint(event.response.checkpoint)
                            currentCoroutineContext()[RunStepServices]?.observeUsage?.invoke(finalizationState.usage)
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
            checkNotNull(finalizationResponse) { "structured model stream ended without a terminal response" }
            finalizationState = finalizationState.advanceModelStep()
            emitStructuredEvent(AgentEvent.StepCompleted(finalizationState.step), emit)
            stepHooks(finalizationState, memory, StepPhase.AfterModelResponse, ModelCallPhase.StructuredFinalization)
            enforce(policies.afterModel(finalizationState))
            stepHooks(finalizationState, memory, StepPhase.AfterStep, ModelCallPhase.StructuredFinalization)
        } catch (stopped: StepStopped) {
            val message = memory.load(emptyList()).transcript.lastAssistantOrEmpty()
            emitStructuredEvent(AgentEvent.Terminated(message, finalizationState.usage, stopped.reason), emit)
            return AgentResult.Terminated(message, finalizationState.usage, stopped.reason)
        } catch (c: CancellationException) {
            withContext(NonCancellable) {
                builder.flushPartial()
                builder.drainCompletedItems().forEach { memory.append(it) }
                builder.lastResponseId()?.let { runCatching { agent.model.abandon(it) } }
            }
            throw c
        } catch (t: Throwable) {
            withContext(NonCancellable) {
                builder.flushPartial()
                builder.drainCompletedItems().forEach { memory.append(it) }
            }
            val error = if (t is ModelFailure) t.error else t.toAgentError()
            val usage = base.usage + (finalizationResponse?.usage ?: org.koaks.framework.model.Usage.ZERO)
            emitStructuredEvent(AgentEvent.Failed(error, usage), emit)
            return AgentResult.Failed(error, usage)
        }
        val finalResponse = checkNotNull(finalizationResponse) { "structured model stream ended without a terminal response" }
        val finalAssistant = builder.reconciledOutput(finalResponse).filterIsInstance<ModelItem.Message>()
            .lastOrNull { it.role == Role.ASSISTANT }
            ?: builder.assistantMessage()
        val usage = base.usage + finalResponse.usage
        if (finalResponse is ModelResponse.Incomplete) {
            emitStructuredEvent(AgentEvent.Incomplete(finalAssistant, usage, finalResponse.reason), emit)
            return AgentResult.Incomplete(finalAssistant, usage, finalResponse.reason)
        }
        if (!finishIfEmpty()) {
            accumulated = finalizationState
            continue
        }
        buffered.forEach { emitStructuredEvent(it, emit) }
        val message = finalAssistant
        emitStructuredEvent(AgentEvent.Completed(message, usage), emit)
        return AgentResult.Completed(message, usage)
        }
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
        identifyRequest: (ModelRequest) -> ModelRequest = { it },
    ): Flow<ModelEvent> {
        var currentRequest = request
        for (hook in agent.hooks) {
            currentRequest = hook.onModelRequest(StepContext(state, currentRequest, phase))
        }
        val checkpoint = currentRequest.checkpoint
        currentRequest = currentRequest.copy(checkpoint = checkpoint.takeIfValidFor(
            currentRequest.items.take(checkpoint?.basis?.itemCount ?: 0),
            kotlinx.datetime.Clock.System.now().toEpochMilliseconds(),
        ))
        agent.contextBudget?.let { budget ->
            val tokens = budget.counter.count(currentRequest)
            require(tokens >= 0) { "input token count must not be negative" }
            if (tokens > budget.maxInputTokens) throw StepStopped(TerminationReason.Custom("context budget exceeded: $tokens > ${budget.maxInputTokens}"))
        }
        currentRequest = identifyRequest(currentRequest)
        currentCoroutineContext().ensureActive()
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

private class StepStopped(val reason: TerminationReason) : RuntimeException()
private class StepHookFailed(cause: Throwable) : RuntimeException("step hook failed", cause)

private val CLIENT_ACTION_KINDS = setOf(
    "computer_call",
    "mcp_approval_request",
    "local_shell_call",
    "custom_tool_call",
)

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
