package org.koaks.framework.loop

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.getAndUpdate
import org.koaks.framework.memory.MemoryProvider
import org.koaks.framework.memory.ThreadId
import org.koaks.framework.middleware.AgentListener
import org.koaks.framework.middleware.Hook
import org.koaks.framework.model.ClientActionHandler
import org.koaks.framework.model.EventDetail
import org.koaks.framework.model.LanguageModel
import org.koaks.framework.model.ModelItem
import org.koaks.framework.model.ModelRequest
import org.koaks.framework.model.OutputFormat
import org.koaks.framework.model.Role
import org.koaks.framework.model.Support
import org.koaks.framework.model.rejectIfUnsupported
import org.koaks.framework.policy.ErrorPolicy
import org.koaks.framework.policy.RunBudget
import org.koaks.framework.policy.SuspendErrorPolicy
import org.koaks.framework.policy.SuspendTerminationPolicy
import org.koaks.framework.policy.TerminationPolicy
import org.koaks.framework.skill.SkillDescriptor
import org.koaks.framework.tool.ToolRegistry
import org.koaks.framework.transport.ModelTransport
import org.koaks.runtime.AgentRuntime
import org.koaks.runtime.acb.AgentHandle

class Agent internal constructor(
    val id: AgentId,
    val name: String,
    val instructions: Instructions,
    val model: LanguageModel,
    val tools: ToolRegistry,
    val hooks: List<Hook>,
    val listeners: List<AgentListener>,
    val termination: TerminationPolicy,
    internal val suspendTermination: SuspendTerminationPolicy?,
    val errorPolicy: ErrorPolicy,
    internal val suspendErrorPolicy: SuspendErrorPolicy?,
    val runBudget: RunBudget,
    private val preparation: AgentPreparation,
    internal val memoryProvider: MemoryProvider?,
    internal val clientActionHandlers: List<ClientActionHandler>,
    private val transport: ModelTransport?,
    private val ownsTransport: Boolean,
) : AutoCloseable {

    internal val definitionUid: Long = AgentDefinitionIds.next()

    private val runner = AgentRunner(this)

    val skillDescriptors: List<SkillDescriptor>
        get() = preparation.skillDescriptors

    suspend fun prepare() {
        preparation.await()
    }

    fun stream(
        input: String,
        thread: ThreadId? = null,
        eventDetail: EventDetail = EventDetail.SEMANTIC,
    ): Flow<AgentEvent> = AgentRuntime.default.stream(this, input, thread = thread, eventDetail = eventDetail)

    fun stream(input: String, thread: String, eventDetail: EventDetail = EventDetail.SEMANTIC): Flow<AgentEvent> =
        stream(input, ThreadId(thread), eventDetail)

    suspend fun run(
        input: String,
        thread: ThreadId? = null,
        eventDetail: EventDetail = EventDetail.SEMANTIC,
    ): AgentResult = AgentRuntime.default.run(this, input, thread = thread, eventDetail = eventDetail)

    suspend fun run(input: String, thread: String, eventDetail: EventDetail = EventDetail.SEMANTIC): AgentResult =
        run(input, ThreadId(thread), eventDetail)

    fun spawn(
        input: String,
        thread: ThreadId? = null,
        eventDetail: EventDetail = EventDetail.SEMANTIC,
    ): AgentHandle = AgentRuntime.default.spawn(this, input, thread = thread, eventDetail = eventDetail)

    fun spawn(input: String, thread: String, eventDetail: EventDetail = EventDetail.SEMANTIC): AgentHandle =
        spawn(input, ThreadId(thread), eventDetail)

    fun resume(thread: ThreadId, eventDetail: EventDetail = EventDetail.SEMANTIC): Flow<AgentEvent> =
        AgentRuntime.default.resume(this, thread, eventDetail = eventDetail)

    fun resume(thread: String, eventDetail: EventDetail = EventDetail.SEMANTIC): Flow<AgentEvent> =
        resume(ThreadId(thread), eventDetail)

    suspend fun resumeRun(thread: ThreadId, eventDetail: EventDetail = EventDetail.SEMANTIC): AgentResult =
        AgentRuntime.default.resumeRun(this, thread, eventDetail = eventDetail)

    suspend fun resumeRun(thread: String, eventDetail: EventDetail = EventDetail.SEMANTIC): AgentResult =
        resumeRun(ThreadId(thread), eventDetail)

    internal fun executeStream(
        input: String?,
        context: List<ModelItem>,
        turn: TurnBuilder,
        checkpoint: org.koaks.framework.model.ProviderCheckpoint? = null,
        eventDetail: EventDetail = EventDetail.SEMANTIC,
    ): Flow<AgentEvent> = flow {
        val prepared = preparation.await()
        emitAll(runner.stream(initialItems(input, context), prepared.instructions.resolve(), turn, checkpoint, eventDetail))
    }

    internal fun executeStructuredStream(
        input: String?,
        context: List<ModelItem>,
        spec: OutputSpec,
        turn: TurnBuilder,
        checkpoint: org.koaks.framework.model.ProviderCheckpoint? = null,
        eventDetail: EventDetail = EventDetail.SEMANTIC,
    ): Flow<AgentEvent> = flow {
        val prepared = preparation.await()
        emitAll(
            runner.streamStructured(
                initialItems(input, context),
                prepared.instructions.resolve(),
                spec,
                turn,
                checkpoint,
                eventDetail,
            ),
        )
    }

    suspend fun runStructured(
        input: String,
        spec: OutputSpec,
        thread: ThreadId? = null,
        eventDetail: EventDetail = EventDetail.SEMANTIC,
    ): AgentResult = AgentRuntime.default.runStructured(this, input, spec, thread = thread, eventDetail = eventDetail)

    suspend fun runStructured(
        input: String,
        spec: OutputSpec,
        thread: String,
        eventDetail: EventDetail = EventDetail.SEMANTIC,
    ): AgentResult = runStructured(input, spec, ThreadId(thread), eventDetail)

    private fun initialItems(input: String?, context: List<ModelItem>): List<ModelItem> {
        val items = buildList {
            addAll(context)
            if (!input.isNullOrEmpty()) add(ModelItem.user(input))
        }
        return applyPrefill(items)
    }

    private fun applyPrefill(items: List<ModelItem>): List<ModelItem> {
        val last = items.lastOrNull() as? ModelItem.Message ?: return items
        if (last.role != Role.ASSISTANT) return items
        if (model.capabilities.assistantPrefill == Support.Supported) return items
        return items + ModelItem.user(
            "Continue the interrupted assistant reply. Do not repeat text already written.",
        )
    }

    internal fun toRequest(
        state: AgentState,
        idempotencyKey: String,
        outputFormat: OutputFormat = OutputFormat.Text,
        eventDetail: EventDetail = EventDetail.SEMANTIC,
    ): ModelRequest {
        when (outputFormat) {
            OutputFormat.JsonObject ->
                model.capabilities.rejectIfUnsupported("json object", model.capabilities.jsonObject, "outputFormat")
            is OutputFormat.JsonSchema ->
                model.capabilities.rejectIfUnsupported("json schema", model.capabilities.jsonSchema, "outputFormat")
            OutputFormat.Text -> Unit
        }
        return ModelRequest(
            instructions = state.instructions,
            items = state.items,
            tools = tools.toSchemas(),
            outputFormat = outputFormat,
            checkpoint = state.checkpoint,
            idempotencyKey = idempotencyKey,
            eventDetail = eventDetail,
        )
    }

    override fun close() {
        if (ownsTransport) transport?.close()
    }
}

private object AgentDefinitionIds {
    private val sequence = MutableStateFlow(0L)
    fun next(): Long = sequence.getAndUpdate { it + 1 }
}

inline fun <R> Agent.use(block: (Agent) -> R): R {
    try {
        return block(this)
    } finally {
        close()
    }
}
