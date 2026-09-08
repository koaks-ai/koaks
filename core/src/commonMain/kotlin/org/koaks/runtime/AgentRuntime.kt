package org.koaks.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.koaks.framework.loop.Agent
import org.koaks.framework.loop.AgentEvent
import org.koaks.framework.loop.AgentId
import org.koaks.framework.loop.AgentResult
import org.koaks.framework.loop.OutputSpec
import org.koaks.framework.memory.MemoryProvider
import org.koaks.framework.memory.ThreadId
import org.koaks.framework.memory.WindowMemoryProvider
import org.koaks.framework.model.EventDetail
import org.koaks.framework.model.ModelItem
import org.koaks.runtime.conversation.ConversationSnapshot
import org.koaks.runtime.coordination.CoordinationFacade
import org.koaks.runtime.internal.RetrySupervisor
import org.koaks.runtime.internal.RunManager
import org.koaks.runtime.internal.TaskGraphExecutor
import org.koaks.runtime.observability.RuntimeEvent
import org.koaks.runtime.observability.RuntimeMetrics
import org.koaks.runtime.policy.Quota
import org.koaks.runtime.policy.SupervisionPolicy
import org.koaks.runtime.run.RunEventHistoryGapException
import org.koaks.runtime.run.RunEventPayload
import org.koaks.runtime.run.RunHandle
import org.koaks.runtime.run.RunId
import org.koaks.runtime.run.RunSnapshot
import org.koaks.runtime.run.SupervisedHandle
import org.koaks.runtime.workflow.TaskGraph

class AgentIdConflictException(val agentId: AgentId) : IllegalStateException(
    "AgentId '${agentId.value}' is already registered to another Agent definition; " +
        "call replaceAgent() while it is idle",
)

class AgentRuntimeConfig {
    var maxConcurrency: Int = Int.MAX_VALUE
    var dispatcher: CoroutineDispatcher = Dispatchers.Default
    var defaultQuota: Quota = Quota.UNLIMITED
    var defaultMemoryProvider: MemoryProvider = WindowMemoryProvider(maxMessages = 40)
    var runEventBufferCapacity: Int = 1024

    internal fun snapshot(): AgentRuntimeConfig = AgentRuntimeConfig().also {
        it.maxConcurrency = maxConcurrency
        it.dispatcher = dispatcher
        it.defaultQuota = defaultQuota
        it.defaultMemoryProvider = defaultMemoryProvider
        it.runEventBufferCapacity = runEventBufferCapacity
    }
}

/** The shared execution facade for direct Agent APIs and explicit managed execution. */
class AgentRuntime internal constructor(config: AgentRuntimeConfig) : AutoCloseable {

    companion object {
        val default: AgentRuntime get() = DefaultRuntimeHolder.get()

        fun configureDefault(configure: AgentRuntimeConfig.() -> Unit) {
            DefaultRuntimeHolder.configure(configure)
        }

        fun shutdownDefault() {
            DefaultRuntimeHolder.shutdown()
        }

        internal fun resetDefaultForTesting(configure: AgentRuntimeConfig.() -> Unit = {}) {
            DefaultRuntimeHolder.resetForTesting(configure)
        }

        internal fun overrideDefaultForTesting(runtime: AgentRuntime) {
            DefaultRuntimeHolder.overrideForTesting(runtime)
        }
    }

    private val manager = RunManager(config)
    private val retries = RetrySupervisor()
    private val graphs = TaskGraphExecutor(this)

    val maxConcurrency: Int get() = manager.maxConcurrency
    val coordination: CoordinationFacade get() = manager.coordination
    val events: SharedFlow<RuntimeEvent> get() = manager.events
    val runs: List<RunSnapshot> get() = manager.runs
    fun snapshot(id: RunId): RunSnapshot? = manager.snapshot(id)
    suspend fun conversationSnapshot(id: ThreadId): ConversationSnapshot? = manager.conversationSnapshot(id)
    fun metrics(): RuntimeMetrics = manager.metrics()
    fun reap(olderThanMillis: Long = 0L): Int = manager.reap(olderThanMillis)
    fun unregister(agentId: AgentId) = manager.unregister(agentId)
    fun replaceAgent(agent: Agent) = manager.replaceAgent(agent)

    suspend fun run(
        agent: Agent,
        input: String,
        priority: Int = 0,
        quota: Quota? = null,
        context: List<ModelItem> = emptyList(),
        thread: ThreadId? = null,
        correlationId: String? = null,
        eventDetail: EventDetail = EventDetail.SEMANTIC,
    ): AgentResult {
        val handle = spawn(agent, input, priority, quota, context, thread, correlationId, eventDetail)
        return try {
            handle.await()
        } catch (cancelled: CancellationException) {
            cancelAndJoin(handle, cancelled.message ?: "runtime run cancelled")
            throw cancelled
        }
    }

    suspend fun run(
        agent: Agent,
        input: String,
        thread: String,
        eventDetail: EventDetail = EventDetail.SEMANTIC,
    ): AgentResult = run(agent, input, thread = ThreadId(thread), eventDetail = eventDetail)

    suspend fun runStructured(
        agent: Agent,
        input: String,
        spec: OutputSpec,
        priority: Int = 0,
        quota: Quota? = null,
        context: List<ModelItem> = emptyList(),
        thread: ThreadId? = null,
        correlationId: String? = null,
        eventDetail: EventDetail = EventDetail.SEMANTIC,
    ): AgentResult {
        val handle = spawnStructured(agent, input, spec, priority, quota, context, thread, correlationId, eventDetail)
        return try {
            handle.await()
        } catch (cancelled: CancellationException) {
            cancelAndJoin(handle, cancelled.message ?: "runtime structured run cancelled")
            throw cancelled
        }
    }

    suspend fun runStructured(
        agent: Agent,
        input: String,
        spec: OutputSpec,
        thread: String,
        eventDetail: EventDetail = EventDetail.SEMANTIC,
    ): AgentResult = runStructured(agent, input, spec, thread = ThreadId(thread), eventDetail = eventDetail)

    fun stream(
        agent: Agent,
        input: String,
        priority: Int = 0,
        quota: Quota? = null,
        context: List<ModelItem> = emptyList(),
        thread: ThreadId? = null,
        correlationId: String? = null,
        eventDetail: EventDetail = EventDetail.SEMANTIC,
    ): Flow<AgentEvent> = flow {
        val handle = spawn(agent, input, priority, quota, context, thread, correlationId, eventDetail)
        try {
            handle.events().collect { envelope ->
                when (val payload = envelope.payload) {
                    is RunEventPayload.Agent -> emit(payload.event)
                    is RunEventPayload.HistoryGap -> throw RunEventHistoryGapException(
                        payload.requestedAfter,
                        payload.oldestAvailable,
                    )
                    is RunEventPayload.Lifecycle -> Unit
                }
            }
        } finally {
            if (handle.isActive || !handle.state.isTerminal) {
                cancelAndJoin(handle, "runtime stream collection stopped")
            }
        }
    }

    fun stream(
        agent: Agent,
        input: String,
        thread: String,
        eventDetail: EventDetail = EventDetail.SEMANTIC,
    ): Flow<AgentEvent> = stream(agent, input, thread = ThreadId(thread), eventDetail = eventDetail)

    fun spawn(
        agent: Agent,
        input: String,
        priority: Int = 0,
        quota: Quota? = null,
        context: List<ModelItem> = emptyList(),
        thread: ThreadId? = null,
        correlationId: String? = null,
        eventDetail: EventDetail = EventDetail.SEMANTIC,
    ): RunHandle = manager.spawn(
        agent,
        input,
        priority,
        quota,
        null,
        context,
        thread,
        null,
        correlationId = correlationId,
        eventDetail = eventDetail,
    )

    fun spawn(
        agent: Agent,
        input: String,
        thread: String,
        eventDetail: EventDetail = EventDetail.SEMANTIC,
    ): RunHandle = spawn(agent, input, thread = ThreadId(thread), eventDetail = eventDetail)

    fun spawnStructured(
        agent: Agent,
        input: String,
        spec: OutputSpec,
        priority: Int = 0,
        quota: Quota? = null,
        context: List<ModelItem> = emptyList(),
        thread: ThreadId? = null,
        correlationId: String? = null,
        eventDetail: EventDetail = EventDetail.SEMANTIC,
    ): RunHandle = manager.spawn(
        agent,
        input,
        priority,
        quota,
        parent = null,
        context = context,
        thread = thread,
        structuredSpec = spec,
        correlationId = correlationId,
        eventDetail = eventDetail,
    )

    fun spawnResume(
        agent: Agent,
        thread: ThreadId,
        priority: Int = 0,
        quota: Quota? = null,
        context: List<ModelItem> = emptyList(),
        correlationId: String? = null,
        eventDetail: EventDetail = EventDetail.SEMANTIC,
    ): RunHandle = manager.spawn(
        agent = agent,
        input = "",
        priority = priority,
        quota = quota,
        parent = null,
        context = context,
        thread = thread,
        structuredSpec = null,
        correlationId = correlationId,
        resume = true,
        eventDetail = eventDetail,
    )

    fun resume(
        agent: Agent,
        thread: ThreadId,
        priority: Int = 0,
        quota: Quota? = null,
        context: List<ModelItem> = emptyList(),
        correlationId: String? = null,
        eventDetail: EventDetail = EventDetail.SEMANTIC,
    ): Flow<AgentEvent> = flow {
        val handle = spawnResume(agent, thread, priority, quota, context, correlationId, eventDetail)
        try {
            handle.events().collect { envelope ->
                when (val payload = envelope.payload) {
                    is RunEventPayload.Agent -> emit(payload.event)
                    is RunEventPayload.HistoryGap -> throw RunEventHistoryGapException(
                        payload.requestedAfter,
                        payload.oldestAvailable,
                    )
                    is RunEventPayload.Lifecycle -> Unit
                }
            }
        } finally {
            if (handle.isActive || !handle.state.isTerminal) {
                cancelAndJoin(handle, "runtime resume collection stopped")
            }
        }
    }

    suspend fun resumeRun(
        agent: Agent,
        thread: ThreadId,
        priority: Int = 0,
        quota: Quota? = null,
        context: List<ModelItem> = emptyList(),
        correlationId: String? = null,
        eventDetail: EventDetail = EventDetail.SEMANTIC,
    ): AgentResult {
        val handle = spawnResume(agent, thread, priority, quota, context, correlationId, eventDetail)
        return try {
            handle.await()
        } catch (cancelled: CancellationException) {
            cancelAndJoin(handle, cancelled.message ?: "runtime resume cancelled")
            throw cancelled
        }
    }

    fun spawnSupervised(
        agent: Agent,
        input: String,
        policy: SupervisionPolicy = SupervisionPolicy(),
        priority: Int = 0,
        quota: Quota? = null,
        context: List<ModelItem> = emptyList(),
        thread: ThreadId? = null,
        eventDetail: EventDetail = EventDetail.SEMANTIC,
    ): SupervisedHandle = retries.spawnSupervised(manager, agent, input, policy, priority, quota, context, thread, eventDetail)

    suspend fun submit(graph: TaskGraph): Map<String, AgentResult> = graphs.submit(graph)

    private suspend fun cancelAndJoin(handle: RunHandle, reason: String) {
        handle.cancel(reason)
        withContext(NonCancellable) { handle.join() }
    }

    override fun close() = manager.close()
}

fun AgentRuntime(configure: AgentRuntimeConfig.() -> Unit = {}): AgentRuntime =
    AgentRuntime(AgentRuntimeConfig().apply(configure))
