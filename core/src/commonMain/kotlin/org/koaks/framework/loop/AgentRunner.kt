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
import org.koaks.framework.memory.ThreadMemory
import org.koaks.framework.memory.forOnlineUse
import org.koaks.framework.memory.upsert
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

    private val logger = KotlinLogging.logger {}

    internal data class LoopRun(val state: AgentState, val items: List<ModelItem>)

    fun stream(
        memory: ThreadMemory,
        instructions: String?,
        eventDetail: EventDetail = EventDetail.SEMANTIC,
        persistTerminalAssistant: Boolean = true,
        seed: List<ModelItem> = emptyList(),
    ): Flow<AgentEvent> = channelFlow {
        runLoop(memory, instructions, eventDetail, persistTerminalAssistant, seed) { send(it) }
    }

    private suspend fun runLoop(
        memory: ThreadMemory,
        instructions: String?,
        eventDetail: EventDetail,
        persistTerminalAssistant: Boolean,
        seed: List<ModelItem> = emptyList(),
        emit: suspend (AgentEvent) -> Unit,
    ): LoopRun {
        val outputMutex = Mutex()
        suspend fun out(event: AgentEvent) {
            outputMutex.withLock {
                agent.listeners.forEach { it.onAgentEvent(event) }
                emit(event)
            }
        }

        val overlay = mutableListOf<ModelItem>()
        seed.forEach { overlay.upsert(it) }
        val builder = TurnBuilder()
        val persistedRefs = HashSet<org.koaks.framework.model.ItemRef>()
        var state = AgentState(instructions = instructions, activeAgentName = agent.name)
        var retries = 0
        var stepKey = newIdempotencyKey()

        suspend fun snapshot(): List<ModelItem> {
            val loaded = memory.load(overlay.takeLast(1)).forOnlineUse().transcript
            return if (loaded.isNotEmpty()) loaded else overlay.toList()
        }

        suspend fun persist(item: ModelItem) {
            overlay.upsert(item)
            memory.append(item)
            persistedRefs += item.ref
        }

        suspend fun persistCompletedBuilderItems() {
            builder.takeStepItems().forEach { item ->
                if (item.ref !in persistedRefs && item !is ModelItem.Message) persist(item)
            }
        }

        suspend fun persistPartial() {
            builder.flushPartial()?.let { persist(it) }
        }

        try {
            while (true) {
                val items = snapshot()
                when (val decision = agent.terminationDecision(state)) {
                    TerminationDecision.Continue -> {}
                    is TerminationDecision.Stop -> {
                        out(AgentEvent.Terminated(items.lastAssistantOrEmpty(), state.usage, decision.reason))
                        return LoopRun(state, items)
                    }
                }

                agent.listeners.forEach { it.onStep(state) }

                var emittedText = false
                var terminalResponse: ModelResponse? = null
                try {
                    val requestItems = agent.applyPrefill(items)
                    val source = modelSource(
                        state,
                        agent.toRequest(state, requestItems, stepKey, OutputFormat.Text, eventDetail),
                        ModelCallPhase.Normal,
                    )
                    builder.reset()
                    source.collect { event ->
                        builder.observe(event)
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
                        if (event is ModelEvent.ToolCallCompleted) persistCompletedBuilderItems()
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
                            persistPartial()
                            out(AgentEvent.Failed(error, state.usage))
                            return LoopRun(state, snapshot())
                        }
                        is Recovery.Substitute -> {
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

                val stepItems = builder.takeStepItems()
                val calls = modelOutput.filterIsInstance<ModelItem.ToolCall>().map { it.toDispatchCall() }
                val actionResults = dispatchClientActions(modelOutput)

                val persistable = stepItems.filter { item ->
                    when (item) {
                        is ModelItem.Message ->
                            item.role != Role.ASSISTANT || persistTerminalAssistant || calls.isNotEmpty() || actionResults.isNotEmpty()
                        else -> true
                    }
                }
                persistable.forEach { persist(it) }
                actionResults.forEach { persist(it) }
                builder.reset()

                if (response is ModelResponse.Incomplete) {
                    out(AgentEvent.Incomplete(assistant, state.usage, response.reason))
                    return LoopRun(state, snapshot())
                }

                if (calls.isEmpty() && actionResults.isEmpty()) {
                    out(AgentEvent.Completed(assistant, state.usage))
                    return LoopRun(state, snapshot())
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
                    val result = when (o) {
                        is ToolOutcome.Success -> ModelItem.ToolResult(
                            callRef = calls[i].ref,
                            output = o.output,
                            isError = false,
                        )
                        is ToolOutcome.Failure -> ModelItem.ToolResult(
                            callRef = calls[i].ref,
                            output = o.error.message,
                            isError = true,
                        )
                    }
                    persist(result)
                }

                outcomes.firstOrNull { it is ToolOutcome.Success && it.returnDirectly }?.let { direct ->
                    val output = (direct as ToolOutcome.Success).output
                    val message = ModelItem.assistant(output)
                    persist(message)
                    out(AgentEvent.Completed(message, state.usage))
                    return LoopRun(state, snapshot())
                }
            }
        } catch (cancelled: CancellationException) {
            persistPartial()
            builder.lastResponseId()?.let { id -> runCatching { agent.model.abandon(id) } }
            throw cancelled
        }
    }

    suspend fun run(
        memory: ThreadMemory,
        instructions: String?,
    ): AgentResult {
        val events = mutableListOf<AgentEvent>()
        runLoop(memory, instructions, EventDetail.SEMANTIC, persistTerminalAssistant = true) { events += it }
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
        seed: List<ModelItem> = emptyList(),
    ): Flow<AgentEvent> = flow {
        runStructured(memory, instructions, spec, eventDetail, seed) { emit(it) }
    }

    private suspend fun runStructured(
        memory: ThreadMemory,
        instructions: String?,
        spec: OutputSpec,
        eventDetail: EventDetail,
        seed: List<ModelItem>,
        emit: suspend (AgentEvent) -> Unit,
    ): AgentResult {
        val events = mutableListOf<AgentEvent>()
        val loop = runLoop(memory, instructions, eventDetail, persistTerminalAssistant = false, seed = seed) { event ->
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
        val loaded = memory.load(emptyList()).forOnlineUse().transcript.ifEmpty { loop.items }
        val convo = loaded + listOfNotNull(formatInstruction)
        val finalizationState = loop.state
        val builder = TurnBuilder()
        val request = agent.toRequest(finalizationState, convo, newIdempotencyKey(), format, eventDetail).copy(tools = emptyList())

        var finalizationResponse: ModelResponse? = null
        try {
            modelSource(finalizationState, request, ModelCallPhase.StructuredFinalization)
                .collect { event ->
                    builder.observe(event)
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
            builder.flushPartial()?.let { memory.append(it) }
            throw c
        } catch (t: Throwable) {
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
        emitStructuredEvent(AgentEvent.StepCompleted(loop.state.step + 1), emit)
        if (finalResponse is ModelResponse.Incomplete) {
            memory.append(finalAssistant)
            emitStructuredEvent(AgentEvent.Incomplete(finalAssistant, usage, finalResponse.reason), emit)
            return AgentResult.Incomplete(finalAssistant, usage, finalResponse.reason)
        }
        val message = ModelItem.assistant(finalAssistant.text)
        memory.append(message)
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
