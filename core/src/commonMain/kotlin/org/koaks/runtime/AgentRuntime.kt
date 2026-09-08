package org.koaks.runtime

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.koaks.framework.loop.Agent
import org.koaks.framework.loop.AgentEvent
import org.koaks.framework.loop.AgentId
import org.koaks.framework.loop.AgentResult
import org.koaks.framework.loop.OutputSpec
import org.koaks.framework.memory.MemoryProvider
import org.koaks.framework.memory.ThreadId
import org.koaks.framework.memory.ThreadMemory
import org.koaks.framework.memory.WindowMemory
import org.koaks.framework.memory.WindowMemoryProvider
import org.koaks.framework.model.AgentError
import org.koaks.framework.model.AgentFrameworkException
import org.koaks.framework.model.EventDetail
import org.koaks.framework.model.ModelItem
import org.koaks.framework.model.Usage
import org.koaks.framework.policy.TerminationReason
import org.koaks.runtime.acb.Acb
import org.koaks.runtime.acb.AcbSnapshot
import org.koaks.runtime.acb.AgentHandle
import org.koaks.runtime.acb.EventSink
import org.koaks.runtime.acb.JournalEventSink
import org.koaks.runtime.acb.InstanceControl
import org.koaks.runtime.acb.LifecycleState
import org.koaks.runtime.acb.RunId
import org.koaks.runtime.acb.RunEventJournal
import org.koaks.runtime.acb.RunEventHistoryGapException
import org.koaks.runtime.acb.RunEventPayload
import org.koaks.runtime.fault.SupervisedHandle
import org.koaks.runtime.fault.SupervisionPolicy
import org.koaks.runtime.fault.Supervisor
import org.koaks.runtime.ipc.IpcHub
import org.koaks.runtime.observe.RuntimeEvent
import org.koaks.runtime.observe.RuntimeMetrics
import org.koaks.runtime.resource.AgentSpawner
import org.koaks.runtime.resource.ChildConversation
import org.koaks.runtime.resource.ChildFailurePolicy
import org.koaks.runtime.resource.InstanceActivityGate
import org.koaks.runtime.resource.Quota
import org.koaks.runtime.resource.ResourceRegistry
import org.koaks.runtime.resource.RuntimeContext
import org.koaks.runtime.sched.Scheduler
import org.koaks.runtime.sched.TaskGraph
import org.koaks.runtime.thread.ThreadRegistry
import org.koaks.runtime.thread.ThreadSnapshot
import kotlin.time.Duration.Companion.milliseconds

private val logger = KotlinLogging.logger {}

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

/** The single execution kernel for direct Agent APIs and explicit managed execution. */
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

    val maxConcurrency: Int = config.maxConcurrency
    private val defaultQuota: Quota = config.defaultQuota
    private val runEventBufferCapacity: Int = config.runEventBufferCapacity.also {
        require(it > 0) { "runEventBufferCapacity must be positive" }
    }

    private val job = SupervisorJob()
    private val scope = CoroutineScope(config.dispatcher + job + CoroutineName("agent-runtime"))
    private val scheduler = Scheduler(config.maxConcurrency)
    private val supervisor = Supervisor()
    private val threads = ThreadRegistry(config.defaultMemoryProvider)

    private val _events = MutableSharedFlow<RuntimeEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<RuntimeEvent> get() = _events.asSharedFlow()

    private fun emit(event: RuntimeEvent) {
        event.runIdOrNull()?.let { id ->
            eventJournals.value[id]?.append(RunEventPayload.Lifecycle(event))
        }
        _events.tryEmit(event)
    }

    val resources: ResourceRegistry = ResourceRegistry()
    val ipc: IpcHub = IpcHub()

    private val runSeq = MutableStateFlow(0L)
    private val acbs = MutableStateFlow<Map<RunId, Acb>>(emptyMap())
    private val handles = MutableStateFlow<Map<RunId, AgentHandle>>(emptyMap())
    private val eventJournals = MutableStateFlow<Map<RunId, RunEventJournal>>(emptyMap())
    private val registrations = MutableStateFlow<Map<AgentId, AgentRegistration>>(emptyMap())
    private val closed = MutableStateFlow(false)

    init {
        job.invokeOnCompletion {
            threads.close()
        }
    }

    val runs: List<AcbSnapshot> get() = acbs.value.values.map { it.snapshot }

    fun snapshot(id: RunId): AcbSnapshot? = acbs.value[id]?.snapshot
    suspend fun threadSnapshot(id: ThreadId): ThreadSnapshot? = threads.snapshot(id)

    fun metrics(): RuntimeMetrics {
        val snaps = acbs.value.values.map { it.snapshot }
        fun count(state: LifecycleState) = snaps.count { it.state == state }
        return RuntimeMetrics(
            total = snaps.size,
            created = count(LifecycleState.CREATED),
            threadQueued = count(LifecycleState.THREAD_QUEUED),
            ready = count(LifecycleState.READY),
            running = count(LifecycleState.RUNNING),
            waiting = count(LifecycleState.WAITING),
            suspended = count(LifecycleState.SUSPENDED),
            committing = count(LifecycleState.COMMITTING),
            finished = count(LifecycleState.FINISHED),
            failed = count(LifecycleState.FAILED),
            cancelled = count(LifecycleState.CANCELLED),
            totalTokens = snaps.sumOf { it.usage.totalTokens },
            totalSteps = snaps.sumOf { it.stepsCompleted },
            totalToolCalls = snaps.sumOf { it.toolCalls },
        )
    }

    fun reap(olderThanMillis: Long = 0L): Int {
        val victims = acbs.value.filter { (_, acb) ->
            val snap = acb.snapshot
            snap.state.isTerminal && (olderThanMillis <= 0L || snap.elapsedMillis >= olderThanMillis)
        }.keys
        if (victims.isEmpty()) return 0
        victims.forEach { ipc.remove(it) }
        acbs.update { it - victims }
        handles.update { it - victims }
        eventJournals.update { it - victims }
        return victims.size
    }

    fun unregister(agentId: AgentId) {
        while (true) {
            val current = registrations.value
            val registered = current[agentId] ?: return
            check(registered.activeRuns == 0) { "agent '$agentId' still has active or queued runs" }
            if (registrations.compareAndSet(current, current - agentId)) return
        }
    }

    fun replaceAgent(agent: Agent) {
        while (true) {
            val current = registrations.value
            val registered = current[agent.id]
            check(registered == null || registered.activeRuns == 0) {
                "agent '${agent.id}' still has active or queued runs"
            }
            val next = current + (
                agent.id to AgentRegistration(
                    definitionUid = agent.definitionUid,
                    agentName = agent.name,
                    activeRuns = 0,
                    definitionVersion = (registered?.definitionVersion ?: 0L) + 1L,
                )
                )
            if (registrations.compareAndSet(current, next)) return
        }
    }

    private fun acquireRegistration(agent: Agent) {
        while (true) {
            val current = registrations.value
            val registered = current[agent.id]
            when {
                registered == null -> {
                    val next = current + (
                        agent.id to AgentRegistration(
                            definitionUid = agent.definitionUid,
                            agentName = agent.name,
                            activeRuns = 1,
                            definitionVersion = 1L,
                        )
                        )
                    if (registrations.compareAndSet(current, next)) return
                }
                registered.definitionUid != agent.definitionUid -> throw AgentIdConflictException(agent.id)
                else -> {
                    val next = current + (agent.id to registered.copy(activeRuns = registered.activeRuns + 1))
                    if (registrations.compareAndSet(current, next)) return
                }
            }
        }
    }

    private fun releaseRegistration(agent: Agent) {
        while (true) {
            val current = registrations.value
            val registered = current[agent.id] ?: return
            if (registered.definitionUid != agent.definitionUid || registered.activeRuns == 0) return
            val next = current + (agent.id to registered.copy(activeRuns = registered.activeRuns - 1))
            if (registrations.compareAndSet(current, next)) return
        }
    }

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
    ): AgentHandle = spawnInternal(
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
    ): AgentHandle = spawn(agent, input, thread = ThreadId(thread), eventDetail = eventDetail)

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
    ): AgentHandle = spawnInternal(
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
    ): AgentHandle = spawnInternal(
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

    private fun spawnInternal(
        agent: Agent,
        input: String,
        priority: Int,
        quota: Quota?,
        parent: RunId?,
        context: List<ModelItem>,
        thread: ThreadId?,
        structuredSpec: OutputSpec?,
        childConversation: ChildConversation? = null,
        failurePolicy: ChildFailurePolicy = ChildFailurePolicy.PROPAGATE,
        correlationId: String? = null,
        resume: Boolean = false,
        eventDetail: EventDetail = EventDetail.SEMANTIC,
    ): AgentHandle {
        check(!closed.value) { "AgentRuntime is closed" }
        require(correlationId == null || correlationId.isNotBlank()) { "correlationId must not be blank" }

        val contextSnapshot = context.toList()
        val parentAcb = parent?.let { parentId ->
            acbs.value[parentId]
                ?: error("parent run '${parentId.value}' does not exist in this runtime")
        }
        val parentSnapshot = parentAcb?.snapshot
        if (parentSnapshot != null) {
            check(!parentSnapshot.state.isTerminal) {
                "cannot spawn a child from terminal parent run '${parentSnapshot.runId.value}'"
            }
        }
        val effectiveThread = when {
            parentSnapshot == null -> thread
            childConversation == ChildConversation.Inherit -> parentSnapshot.threadId
            childConversation == ChildConversation.Ephemeral -> null
            childConversation is ChildConversation.Thread -> {
                require(childConversation.id != parentSnapshot.threadId) {
                    "child conversation thread '${childConversation.id.value}' is the parent's active thread; " +
                        "use ChildConversation.Inherit"
                }
                childConversation.id
            }
            else -> error("child spawn requires an explicit ChildConversation")
        }
        val inheritsParentRun = parentSnapshot != null &&
            childConversation == ChildConversation.Inherit &&
            effectiveThread != null &&
            effectiveThread == parentSnapshot.threadId
        val runOwner = effectiveThread != null && !inheritsParentRun

        val runId = RunId(runSeq.getAndUpdate { it + 1 })
        acquireRegistration(agent)
        var completionOwnsRegistration = false
        var runReservation: ThreadRegistry.RunReservation? = null
        var childLinked = false
        try {
            runReservation = if (runOwner) {
                threads.reserve(effectiveThread!!, runId, agent)
            } else {
                null
            }
            if (parentAcb != null) {
                check(parentAcb.tryAddChild(runId, failurePolicy)) {
                    "parent run '${parentAcb.runId.value}' is no longer accepting children"
                }
                childLinked = true
            }
            val effectiveCorrelationId = correlationId ?: parentSnapshot?.correlationId
            val acb = Acb(runId, agent.id, agent.name, effectiveThread, priority, parent, effectiveCorrelationId)
            if (runReservation != null) acb.setState(LifecycleState.THREAD_QUEUED)
            acbs.update { it + (runId to acb) }
            val eventJournal = RunEventJournal(
                runId,
                agent.id,
                effectiveThread,
                effectiveCorrelationId,
                runEventBufferCapacity,
            )
            eventJournals.update { it + (runId to eventJournal) }
            val sink = JournalEventSink(eventJournal)
            emit(RuntimeEvent.Spawned(runId, agent.id, agent.name, effectiveThread, priority, parent))

            val effectiveQuota = quota ?: defaultQuota
            val control = InstanceControl()
            ipc.mailbox(runId)

            val childSpawner = AgentSpawner {
                    childAgent, childInput, childPriority, childQuota, childRefs,
                    childFailurePolicy, conversation ->
                spawnInternal(
                    agent = childAgent,
                    input = childInput,
                    priority = childPriority,
                    quota = childQuota,
                    parent = runId,
                    context = childRefs,
                    thread = null,
                    structuredSpec = null,
                    childConversation = conversation,
                    failurePolicy = childFailurePolicy,
                    correlationId = effectiveCorrelationId,
                    eventDetail = eventDetail,
                )
            }
            val runtimeContext = RuntimeContext(
                resources,
                runId,
                agent.id,
                effectiveThread,
                effectiveCorrelationId,
                ipc,
                childSpawner,
            )
            val gate = InstanceActivityGate(runId, acb, ::emit)

            val deferred: Deferred<AgentResult> = scope.async(runtimeContext + gate) {
                try {
                    val refs = contextSnapshot
                    runInstance(
                        gate,
                        agent,
                        input,
                        refs,
                        acb,
                        control,
                        priority,
                        effectiveQuota,
                        sink,
                        effectiveThread,
                        runOwner,
                        runReservation,
                        structuredSpec,
                        resume,
                        eventDetail,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    val snapshot = acb.snapshot
                    if (!snapshot.state.isTerminal) {
                        val error = AgentError.ModelError(
                            message = failure.message ?: "runtime instance failed unexpectedly",
                            retriable = false,
                            cause = failure,
                        )
                        acb.markFailed(error, snapshot.usage)
                        emit(RuntimeEvent.Failed(runId, agent.id, effectiveThread, error))
                        cancelDescendants(runId, "parent $runId failed")
                    }
                    throw failure
                }
            }
            deferred.invokeOnCompletion { cause ->
                runReservation?.cancel()
                releaseRegistration(agent)
                if (cause is CancellationException) {
                    acb.markCancelled()
                    if (acb.snapshot.state == LifecycleState.CANCELLED && acb.claimCancellationEvent()) {
                        emit(RuntimeEvent.Cancelled(runId, agent.id, effectiveThread))
                    }
                }
                sink.close(cause)
            }
            completionOwnsRegistration = true
            val handle = AgentHandle(
                runId,
                agent.id,
                effectiveThread,
                effectiveCorrelationId,
                acb,
                control,
                deferred,
                failurePolicy,
                eventJournal,
            )
            handles.update { it + (runId to handle) }

            if (closed.value) {
                handle.cancel("agent runtime closed")
                handles.update { it - runId }
                acbs.update { it - runId }
                eventJournals.update { it - runId }
                ipc.remove(runId)
                error("AgentRuntime is closed")
            }
            return handle
        } finally {
            if (!completionOwnsRegistration) {
                if (childLinked) parentAcb?.removeChild(runId)
                runReservation?.cancel()
                eventJournals.update { it - runId }
                releaseRegistration(agent)
            }
        }
    }

    private suspend fun runInstance(
        gate: InstanceActivityGate,
        agent: Agent,
        input: String,
        contextPrefix: List<ModelItem>,
        acb: Acb,
        control: InstanceControl,
        priority: Int,
        quota: Quota,
        sink: EventSink,
        thread: ThreadId?,
        runOwner: Boolean,
        runReservation: ThreadRegistry.RunReservation?,
        structuredSpec: OutputSpec?,
        resume: Boolean,
        eventDetail: EventDetail,
    ): AgentResult {
        var threadLease: ThreadRegistry.ThreadLease? = null
        try {
            val memory = if (thread != null) {
                if (runOwner) {
                    val lease = requireNotNull(runReservation).await().also { threadLease = it }
                    lease.memory().let { if (it === org.koaks.framework.memory.NoMemory) WindowMemory(Int.MAX_VALUE) else it }
                } else {
                    val owner = threadOwnerRunId(requireNotNull(acb.snapshot.parent), thread)
                    threads.joinActiveRun(thread, owner, agent)
                    threads.memory(thread)
                }
            } else {
                WindowMemory(Int.MAX_VALUE)
            }

            acb.markReady()
            val result = scheduler.withSlot(priority) { lease ->
                gate.attachLease(lease)
                acb.markRunning()
                emit(RuntimeEvent.Running(acb.runId, agent.id, thread, agent.name))
                runWithQuota(
                    agent,
                    input.takeUnless { resume },
                    contextPrefix,
                    memory,
                    acb,
                    control,
                    quota,
                    sink,
                    structuredSpec,
                    eventDetail,
                ) {}
            }
            acb.sealChildren()
            if (!acb.snapshot.state.isTerminal && hasActiveChildren(acb.runId)) {
                acb.setState(LifecycleState.WAITING)
                emit(RuntimeEvent.Waiting(acb.runId, agent.id, thread))
            }
            val childFailure = awaitChildren(
                acb.runId,
                reportCapturedFailures = true,
                reportCapturedCancellations = result is AgentResult.Completed,
            )
            if (result !is AgentResult.Failed && childFailure != null) {
                val error = AgentError.ModelError(
                    message = "child run '${childFailure.runId.value}' failed: ${childFailure.error.message}",
                    retriable = false,
                    cause = childFailure.error.cause,
                )
                acb.markFailed(error, result.usage)
                emit(RuntimeEvent.Failed(acb.runId, agent.id, thread, error))
                sink.emit(AgentEvent.Failed(error, result.usage))
                return AgentResult.Failed(error, result.usage)
            }
            when (result) {
                is AgentResult.Completed -> if (acb.beginCommitting()) {
                    withContext(NonCancellable) {
                        acb.markFinished(result.usage)
                        emit(RuntimeEvent.Finished(acb.runId, agent.id, thread, result.usage))
                    }
                }
                is AgentResult.Incomplete -> if (acb.beginCommitting()) {
                    withContext(NonCancellable) {
                        acb.markFinished(result.usage)
                        emit(
                            RuntimeEvent.Incomplete(
                                acb.runId,
                                agent.id,
                                thread,
                                result.reason,
                                result.usage,
                            ),
                        )
                    }
                }
                is AgentResult.Terminated -> if (acb.beginCommitting()) {
                    withContext(NonCancellable) {
                        acb.markFinished(result.usage)
                        emit(RuntimeEvent.Terminated(acb.runId, agent.id, thread, result.reason))
                    }
                }
                is AgentResult.Failed -> withContext(NonCancellable) {
                }
            }
            return result
        } catch (cancelled: CancellationException) {
            acb.sealChildren()
            acb.markCancelled()
            if (acb.snapshot.state == LifecycleState.CANCELLED && acb.claimCancellationEvent()) {
                emit(RuntimeEvent.Cancelled(acb.runId, agent.id, thread))
            }
            cancelDescendants(acb.runId, "parent ${acb.runId} cancelled")
            withContext(NonCancellable) {
                awaitChildren(
                    acb.runId,
                    reportCapturedFailures = false,
                    reportCapturedCancellations = false,
                )
            }
            throw cancelled
        } catch (failure: Throwable) {
            acb.sealChildren()
            cancelDescendants(acb.runId, "parent ${acb.runId} failed")
            withContext(NonCancellable) {
                awaitChildren(
                    acb.runId,
                    reportCapturedFailures = false,
                    reportCapturedCancellations = false,
                )
            }
            throw failure
        } finally {
            withContext(NonCancellable) {
                threadLease?.release()
            }
        }
    }

    private fun threadOwnerRunId(from: RunId, thread: ThreadId): RunId {
        var current = from
        while (true) {
            val parent = acbs.value[current]?.snapshot?.parent ?: return current
            if (acbs.value[parent]?.snapshot?.threadId != thread) return current
            current = parent
        }
    }

    private suspend fun runWithQuota(
        agent: Agent,
        input: String?,
        contextPrefix: List<ModelItem>,
        memory: ThreadMemory,
        acb: Acb,
        control: InstanceControl,
        quota: Quota,
        sink: EventSink,
        structuredSpec: OutputSpec?,
        eventDetail: EventDetail,
        terminalUsage: (Usage) -> Unit,
    ): AgentResult {
        val wall = quota.wallClockMillis
            ?: return collectStream(
                agent,
                input,
                contextPrefix,
                memory,
                acb,
                control,
                quota,
                sink,
                structuredSpec,
                eventDetail,
                terminalUsage,
            )
        return try {
            withTimeout(wall.milliseconds) {
                collectStream(
                    agent,
                    input,
                    contextPrefix,
                    memory,
                    acb,
                    control,
                    quota,
                    sink,
                    structuredSpec,
                    eventDetail,
                    terminalUsage,
                )
            }
        } catch (_: TimeoutCancellationException) {
            val error = AgentError.Timeout("agent wall-clock", wall)
            val usage = acb.snapshot.usage
            acb.markFailed(error, usage)
            emit(RuntimeEvent.Failed(acb.runId, acb.snapshot.agentId, acb.snapshot.threadId, error))
            cancelDescendants(acb.runId, "parent ${acb.runId} timed out")
            sink.emit(AgentEvent.Failed(error, usage))
            AgentResult.Failed(error, usage)
        }
    }

    private suspend fun collectStream(
        agent: Agent,
        input: String?,
        contextPrefix: List<ModelItem>,
        memory: ThreadMemory,
        acb: Acb,
        control: InstanceControl,
        quota: Quota,
        sink: EventSink,
        structuredSpec: OutputSpec?,
        eventDetail: EventDetail,
        terminalUsage: (Usage) -> Unit,
    ): AgentResult {
        var terminal: AgentEvent.Terminal? = null
        var lastFailure: AgentError? = null
        var usage = Usage.ZERO
        var steps = 0
        var toolCalls = 0
        val stepText = StringBuilder()
        var lastAssistant = ""

        try {
            val events = if (structuredSpec == null) {
                agent.executeStream(input, memory, eventDetail, contextPrefix)
            } else {
                agent.executeStructuredStream(input, memory, structuredSpec, eventDetail, contextPrefix)
            }
            events.collect { event ->
                if (control.isPaused) {
                    acb.setState(LifecycleState.SUSPENDED)
                    emit(RuntimeEvent.Suspended(acb.runId, agent.id, acb.snapshot.threadId))
                    control.awaitResumed()
                    acb.markRunning()
                    emit(RuntimeEvent.Resumed(acb.runId, agent.id, acb.snapshot.threadId))
                }

                sink.emit(event)
                acb.observe(event)
                when (event) {
                    is AgentEvent.TextDelta -> stepText.append(event.text)
                    is AgentEvent.ToolCallRequested -> {
                        toolCalls++
                        if (quota.maxToolCalls != null && toolCalls > quota.maxToolCalls) {
                            throw QuotaSignal("maxToolCalls=${quota.maxToolCalls}")
                        }
                    }
                    is AgentEvent.StepCompleted -> {
                        steps++
                        lastAssistant = stepText.toString()
                        stepText.clear()
                        if (quota.maxSteps != null && steps >= quota.maxSteps) {
                            throw QuotaSignal("maxSteps=${quota.maxSteps}")
                        }
                    }
                    is AgentEvent.Terminal -> {
                        terminal = event
                        usage = event.usage
                        terminalUsage(usage)
                    }
                    is AgentEvent.Failed -> {
                        lastFailure = event.error
                        if (event.usage != Usage.ZERO) usage = event.usage
                    }
                    else -> Unit
                }
            }
        } catch (quotaSignal: QuotaSignal) {
            val currentUsage = acb.snapshot.usage
            acb.markCancelled()
            if (acb.snapshot.state == LifecycleState.CANCELLED && acb.claimCancellationEvent()) {
                emit(RuntimeEvent.Cancelled(acb.runId, agent.id, acb.snapshot.threadId))
            }
            cancelDescendants(acb.runId, "parent ${acb.runId} quota exceeded")
            val reason = TerminationReason.Custom("quota exceeded: ${quotaSignal.dimension}")
            val message = ModelItem.assistant(stepText.toString().ifEmpty { lastAssistant })
            sink.emit(AgentEvent.Terminated(message, currentUsage, reason))
            return AgentResult.Terminated(message, currentUsage, reason)
        } catch (failure: AgentFrameworkException) {
            val currentUsage = acb.snapshot.usage
            val error = failure.error
            val event = AgentEvent.Failed(error, currentUsage)
            acb.markFailed(error, currentUsage)
            emit(RuntimeEvent.Failed(acb.runId, agent.id, acb.snapshot.threadId, error))
            cancelDescendants(acb.runId, "parent ${acb.runId} failed")
            agent.listeners.forEach { it.onAgentEvent(event) }
            sink.emit(event)
            return AgentResult.Failed(error, currentUsage)
        }

        return when (val term = terminal) {
            is AgentEvent.Completed -> AgentResult.Completed(term.message, usage)
            is AgentEvent.Incomplete -> AgentResult.Incomplete(term.message, usage, term.reason)
            is AgentEvent.Terminated -> AgentResult.Terminated(term.message, usage, term.reason)
            null -> {
                val error = lastFailure ?: AgentError.ModelError("agent produced no terminal event", retriable = false)
                acb.markFailed(error, usage)
                emit(RuntimeEvent.Failed(acb.runId, agent.id, acb.snapshot.threadId, error))
                cancelDescendants(acb.runId, "parent ${acb.runId} failed")
                AgentResult.Failed(error, usage)
            }
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
    ): SupervisedHandle {
        val latest = MutableStateFlow<AgentHandle?>(null)
        val deferred: Deferred<AgentResult> = scope.async {
            supervisor.run(
                agentName = agent.name,
                initialInput = input,
                policy = policy,
                onRetrying = { attempt, delayMillis ->
                    val current = latest.value
                    emit(
                        RuntimeEvent.Retrying(
                            runId = current?.runId,
                            agentId = agent.id,
                            agentName = agent.name,
                            threadId = current?.threadId ?: thread,
                            attempt = attempt,
                            delayMillis = delayMillis,
                        ),
                    )
                },
                onCircuitOpen = {
                    val current = latest.value
                    emit(
                        RuntimeEvent.CircuitOpen(
                            runId = current?.runId,
                            agentId = agent.id,
                            agentName = agent.name,
                            threadId = current?.threadId ?: thread,
                        ),
                    )
                },
            ) { attemptInput ->
                val handle = spawn(agent, attemptInput, priority, quota, context, thread, eventDetail = eventDetail)
                latest.value = handle
                handle.await()
            }
        }
        return SupervisedHandle(deferred) { reason -> latest.value?.cancel(reason) }
    }

    suspend fun submit(graph: TaskGraph): Map<String, AgentResult> = coroutineScope {
        val results = graph.nodes.associate { it.id to CompletableDeferred<AgentResult>() }
        graph.nodes.forEach { node ->
            launch {
                try {
                    val deps = node.dependsOn.associateWith { results.getValue(it).await() }
                    val result = run(node.agent, node.input(deps), node.priority)
                    results.getValue(node.id).complete(result)
                } catch (failure: Throwable) {
                    results.getValue(node.id).completeExceptionally(failure)
                    throw failure
                }
            }
        }
        results.mapValues { it.value.await() }
    }

    private fun cancelDescendants(id: RunId, reason: String) {
        val acb = acbs.value[id] ?: return
        acb.snapshot.children.forEach { child ->
            handles.value[child]?.cancel(reason)
            cancelDescendants(child, reason)
        }
    }

    private suspend fun awaitChildren(
        id: RunId,
        reportCapturedFailures: Boolean = true,
        reportCapturedCancellations: Boolean = true,
    ): ChildFailure? {
        val parentAcb = acbs.value[id] ?: return null
        val children = parentAcb.snapshot.children
        var firstFailure: ChildFailure? = null
        children.forEach { child ->
            val handle = handles.value[child] ?: return@forEach
            val failurePolicy = parentAcb.childFailurePolicy(child)

            fun recordFailure(error: AgentError, cancellation: Boolean = false) {
                when (failurePolicy) {
                    ChildFailurePolicy.PROPAGATE -> if (firstFailure == null) {
                        firstFailure = ChildFailure(child, error)
                    }
                    ChildFailurePolicy.CAPTURE -> if (
                        reportCapturedFailures && (!cancellation || reportCapturedCancellations)
                    ) {
                        reportUnhandledChildFailure(id, handle, error)
                    }
                }
            }

            try {
                val result = handle.awaitForParent()
                if (result is AgentResult.Failed) recordFailure(result.error)
            } catch (cancelled: CancellationException) {
                currentCoroutineContext().ensureActive()
                recordFailure(
                    AgentError.ModelError(
                        message = cancelled.message ?: "child run was cancelled",
                        retriable = false,
                        cause = cancelled,
                    ),
                    cancellation = true,
                )
            } catch (failure: Throwable) {
                recordFailure(
                    handle.snapshot.error ?: AgentError.ModelError(
                        message = failure.message ?: "child run failed unexpectedly",
                        retriable = false,
                        cause = failure,
                    ),
                )
            }
        }
        return firstFailure
    }

    private fun reportUnhandledChildFailure(parent: RunId, handle: AgentHandle, error: AgentError) {
        if (handle.isFailureObserved || !handle.claimUnhandledFailure()) return
        logger.warn {
            "captured child run '${handle.runId.value}' failed without its result being consumed: ${error.message}"
        }
        emit(
            RuntimeEvent.UnhandledChildFailure(
                parentRunId = parent,
                childRunId = handle.runId,
                childAgentId = handle.agentId,
                error = error,
            ),
        )
    }

    private fun hasActiveChildren(id: RunId): Boolean =
        acbs.value[id]?.snapshot?.children.orEmpty().any { child -> handles.value[child]?.isActive == true }

    private suspend fun cancelAndJoin(handle: AgentHandle, reason: String) {
        handle.cancel(reason)
        withContext(NonCancellable) { handle.join() }
    }

    override fun close() {
        if (!closed.compareAndSet(expect = false, update = true)) return
        handles.value.values.forEach { it.cancel("agent runtime closed") }
        job.cancel(CancellationException("agent runtime closed"))
        ipc.closeAll()
        handles.value = emptyMap()
        acbs.value = emptyMap()
        eventJournals.value = emptyMap()
        registrations.value = emptyMap()
    }
}

private sealed interface DefaultRuntimeState {
    data class Configured(val config: AgentRuntimeConfig) : DefaultRuntimeState
    data class Running(val runtime: AgentRuntime) : DefaultRuntimeState
    data object Closed : DefaultRuntimeState
}

private data class AgentRegistration(
    val definitionUid: Long,
    val agentName: String,
    val activeRuns: Int,
    val definitionVersion: Long,
)

private data class ChildFailure(
    val runId: RunId,
    val error: AgentError,
)

private object DefaultRuntimeHolder {
    private val state = MutableStateFlow<DefaultRuntimeState>(DefaultRuntimeState.Configured(AgentRuntimeConfig()))
    private val hookInstalled = MutableStateFlow(false)

    fun configure(configure: AgentRuntimeConfig.() -> Unit) {
        while (true) {
            val current = state.value
            check(current is DefaultRuntimeState.Configured) { "default AgentRuntime is already initialized or closed" }
            val next = DefaultRuntimeState.Configured(current.config.snapshot().apply(configure))
            if (state.compareAndSet(current, next)) return
        }
    }

    fun get(): AgentRuntime {
        while (true) {
            when (val current = state.value) {
                is DefaultRuntimeState.Running -> return current.runtime
                DefaultRuntimeState.Closed -> error("default AgentRuntime is closed")
                is DefaultRuntimeState.Configured -> {
                    val runtime = AgentRuntime(current.config.snapshot())
                    if (state.compareAndSet(current, DefaultRuntimeState.Running(runtime))) {
                        installHookOnce()
                        return runtime
                    }
                    runtime.close()
                }
            }
        }
    }

    fun shutdown() {
        while (true) {
            when (val current = state.value) {
                DefaultRuntimeState.Closed -> return
                is DefaultRuntimeState.Configured -> if (state.compareAndSet(current, DefaultRuntimeState.Closed)) return
                is DefaultRuntimeState.Running -> if (state.compareAndSet(current, DefaultRuntimeState.Closed)) {
                    current.runtime.close()
                    return
                }
            }
        }
    }

    fun resetForTesting(configure: AgentRuntimeConfig.() -> Unit) {
        (state.value as? DefaultRuntimeState.Running)?.runtime?.close()
        state.value = DefaultRuntimeState.Configured(AgentRuntimeConfig().apply(configure))
    }

    fun overrideForTesting(runtime: AgentRuntime) {
        val current = state.value
        if (current is DefaultRuntimeState.Running && current.runtime !== runtime) current.runtime.close()
        state.value = DefaultRuntimeState.Running(runtime)
        installHookOnce()
    }

    private fun installHookOnce() {
        if (hookInstalled.compareAndSet(expect = false, update = true)) {
            installDefaultRuntimeShutdownHook(::shutdown)
        }
    }
}

private class QuotaSignal(val dimension: String) : CancellationException("quota exceeded: $dimension")

private fun RuntimeEvent.runIdOrNull(): RunId? = when (this) {
    is RuntimeEvent.Spawned -> runId
    is RuntimeEvent.Running -> runId
    is RuntimeEvent.Waiting -> runId
    is RuntimeEvent.Suspended -> runId
    is RuntimeEvent.Resumed -> runId
    is RuntimeEvent.Finished -> runId
    is RuntimeEvent.Incomplete -> runId
    is RuntimeEvent.Terminated -> runId
    is RuntimeEvent.Failed -> runId
    is RuntimeEvent.Cancelled -> runId
    is RuntimeEvent.UnhandledChildFailure -> childRunId
    is RuntimeEvent.Retrying -> runId
    is RuntimeEvent.CircuitOpen -> runId
}

fun AgentRuntime(configure: AgentRuntimeConfig.() -> Unit = {}): AgentRuntime =
    AgentRuntime(AgentRuntimeConfig().apply(configure))
