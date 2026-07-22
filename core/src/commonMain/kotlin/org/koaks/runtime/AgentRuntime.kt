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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
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
import org.koaks.framework.memory.TurnCommitBuffer
import org.koaks.framework.memory.WindowMemoryProvider
import org.koaks.framework.model.AgentError
import org.koaks.framework.model.Message
import org.koaks.framework.model.Usage
import org.koaks.framework.policy.TerminationReason
import org.koaks.runtime.acb.Acb
import org.koaks.runtime.acb.AcbSnapshot
import org.koaks.runtime.acb.AgentHandle
import org.koaks.runtime.acb.ChannelEventSink
import org.koaks.runtime.acb.EventSink
import org.koaks.runtime.acb.InstanceControl
import org.koaks.runtime.acb.LifecycleState
import org.koaks.runtime.acb.RunId
import org.koaks.runtime.acb.TurnId
import org.koaks.runtime.context.ContextRef
import org.koaks.runtime.context.ContextStore
import org.koaks.runtime.fault.SupervisedHandle
import org.koaks.runtime.fault.SupervisionPolicy
import org.koaks.runtime.fault.Supervisor
import org.koaks.runtime.ipc.IpcHub
import org.koaks.runtime.observe.RuntimeEvent
import org.koaks.runtime.observe.RuntimeMetrics
import org.koaks.runtime.resource.AgentSpawner
import org.koaks.runtime.resource.InstanceActivityGate
import org.koaks.runtime.resource.Quota
import org.koaks.runtime.resource.ResourceRegistry
import org.koaks.runtime.resource.RuntimeContext
import org.koaks.runtime.sched.Scheduler
import org.koaks.runtime.sched.TaskGraph
import org.koaks.runtime.thread.ThreadRegistry
import org.koaks.runtime.thread.ThreadSnapshot
import org.koaks.runtime.thread.TurnContext
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

    internal fun snapshot(): AgentRuntimeConfig = AgentRuntimeConfig().also {
        it.maxConcurrency = maxConcurrency
        it.dispatcher = dispatcher
        it.defaultQuota = defaultQuota
        it.defaultMemoryProvider = defaultMemoryProvider
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

    private val job = SupervisorJob()
    private val scope = CoroutineScope(config.dispatcher + job + CoroutineName("agent-runtime"))
    private val scheduler = Scheduler(config.maxConcurrency)
    private val supervisor = Supervisor()
    private val threads = ThreadRegistry(config.defaultMemoryProvider)

    private val _events = MutableSharedFlow<RuntimeEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<RuntimeEvent> get() = _events.asSharedFlow()

    private fun emit(event: RuntimeEvent) {
        _events.tryEmit(event)
    }

    val resources: ResourceRegistry = ResourceRegistry()
    val ipc: IpcHub = IpcHub()
    val context: ContextStore = ContextStore()

    private val runSeq = MutableStateFlow(0L)
    private val turnSeq = MutableStateFlow(0L)
    private val acbs = MutableStateFlow<Map<RunId, Acb>>(emptyMap())
    private val handles = MutableStateFlow<Map<RunId, AgentHandle>>(emptyMap())
    private val registrations = MutableStateFlow<Map<AgentId, AgentRegistration>>(emptyMap())
    private val turnContexts = MutableStateFlow<Map<TurnId, TurnContext>>(emptyMap())
    private val closed = MutableStateFlow(false)

    init {
        job.invokeOnCompletion {
            threads.close()
            turnContexts.value = emptyMap()
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
        contextRefs: List<ContextRef> = emptyList(),
        thread: ThreadId? = null,
    ): AgentResult {
        val handle = spawn(agent, input, priority, quota, contextRefs, thread)
        return try {
            handle.await()
        } catch (cancelled: CancellationException) {
            cancelAndJoin(handle, cancelled.message ?: "runtime run cancelled")
            throw cancelled
        }
    }

    suspend fun run(agent: Agent, input: String, thread: String): AgentResult =
        run(agent, input, thread = ThreadId(thread))

    suspend fun runStructured(
        agent: Agent,
        input: String,
        spec: OutputSpec,
        priority: Int = 0,
        quota: Quota? = null,
        contextRefs: List<ContextRef> = emptyList(),
        thread: ThreadId? = null,
    ): AgentResult {
        val handle = spawnInternal(
            agent,
            input,
            priority,
            quota,
            parent = null,
            contextRefs = contextRefs,
            sink = EventSink.NONE,
            thread = thread,
            structuredSpec = spec,
        )
        return try {
            handle.await()
        } catch (cancelled: CancellationException) {
            cancelAndJoin(handle, cancelled.message ?: "runtime structured run cancelled")
            throw cancelled
        }
    }

    suspend fun runStructured(agent: Agent, input: String, spec: OutputSpec, thread: String): AgentResult =
        runStructured(agent, input, spec, thread = ThreadId(thread))

    fun stream(
        agent: Agent,
        input: String,
        priority: Int = 0,
        quota: Quota? = null,
        contextRefs: List<ContextRef> = emptyList(),
        thread: ThreadId? = null,
    ): Flow<AgentEvent> = channelFlow {
        val handle = spawnInternal(
            agent = agent,
            input = input,
            priority = priority,
            quota = quota,
            parent = null,
            contextRefs = contextRefs,
            sink = ChannelEventSink(channel),
            thread = thread,
            structuredSpec = null,
        )
        try {
            handle.join()
        } finally {
            if (handle.isActive || !handle.state.isTerminal) {
                cancelAndJoin(handle, "runtime stream collection stopped")
            }
        }
    }.buffer(Channel.RENDEZVOUS)

    fun stream(agent: Agent, input: String, thread: String): Flow<AgentEvent> =
        stream(agent, input, thread = ThreadId(thread))

    fun spawn(
        agent: Agent,
        input: String,
        priority: Int = 0,
        quota: Quota? = null,
        contextRefs: List<ContextRef> = emptyList(),
        thread: ThreadId? = null,
    ): AgentHandle = spawnInternal(agent, input, priority, quota, null, contextRefs, EventSink.NONE, thread, null)

    fun spawn(agent: Agent, input: String, thread: String): AgentHandle =
        spawn(agent, input, thread = ThreadId(thread))

    private fun spawnInternal(
        agent: Agent,
        input: String,
        priority: Int,
        quota: Quota?,
        parent: RunId?,
        contextRefs: List<ContextRef>,
        sink: EventSink,
        thread: ThreadId?,
        structuredSpec: OutputSpec?,
        inheritedTurnContext: TurnContext? = null,
    ): AgentHandle {
        check(!closed.value) { "AgentRuntime is closed" }

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
            thread == null -> parentSnapshot.threadId
            else -> thread
        }
        val inheritsParentTurn = parentSnapshot != null &&
            effectiveThread != null &&
            effectiveThread == parentSnapshot.threadId &&
            parentSnapshot.turnId != null
        val turnOwner = effectiveThread != null && !inheritsParentTurn

        val runId = RunId(runSeq.getAndUpdate { it + 1 })
        val turnId = if (inheritsParentTurn) {
            parentSnapshot!!.turnId
        } else {
            effectiveThread?.let { TurnId(turnSeq.getAndUpdate { value -> value + 1 }) }
        }
        val turnContext = when {
            turnId == null -> null
            inheritsParentTurn -> inheritedTurnContext
                ?: turnContexts.value[turnId]
                ?: error("turn '${turnId.value}' has no active context")
            else -> TurnContext(effectiveThread!!, turnId, Message.user(input))
        }
        acquireRegistration(agent)
        var completionOwnsRegistration = false
        var turnReservation: ThreadRegistry.TurnReservation? = null
        var turnContextRegistered = false
        var childLinked = false
        try {
            turnReservation = if (turnOwner) {
                threads.reserve(effectiveThread!!, turnId!!, agent)
            } else {
                null
            }
            if (turnOwner && turnContext != null) {
                turnContexts.update { current ->
                    check(turnId !in current) { "turn '${turnId!!.value}' is already registered" }
                    current + (turnId!! to turnContext)
                }
                turnContextRegistered = true
            }
            if (parentAcb != null) {
                check(parentAcb.tryAddChild(runId)) {
                    "parent run '${parentAcb.runId.value}' is no longer accepting children"
                }
                childLinked = true
            }
            val acb = Acb(runId, agent.id, agent.name, effectiveThread, turnId, priority, parent)
            if (turnReservation != null) acb.setState(LifecycleState.THREAD_QUEUED)
            acbs.update { it + (runId to acb) }
            emit(RuntimeEvent.Spawned(runId, agent.id, agent.name, effectiveThread, turnId, priority, parent))

            val effectiveQuota = quota ?: defaultQuota
            val control = InstanceControl()
            ipc.mailbox(runId)

            val childSpawner = AgentSpawner { childAgent, childInput, childPriority, childQuota, childRefs, childThread ->
                spawnInternal(
                    agent = childAgent,
                    input = childInput,
                    priority = childPriority,
                    quota = childQuota,
                    parent = runId,
                    contextRefs = childRefs,
                    sink = EventSink.NONE,
                    thread = childThread,
                    structuredSpec = null,
                    inheritedTurnContext = turnContext,
                )
            }
            val runtimeContext = RuntimeContext(resources, runId, agent.id, effectiveThread, turnId, ipc, context, childSpawner)
            val gate = InstanceActivityGate(runId, acb, ::emit) { turnContext?.markSideEffect() }

            val deferred: Deferred<AgentResult> = scope.async(runtimeContext + gate) {
                try {
                    val refs = resolveContextPrefix(contextRefs, requester = parent ?: runId)
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
                        turnId,
                        turnContext,
                        turnOwner,
                        turnReservation,
                        structuredSpec,
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
                        emit(RuntimeEvent.Failed(runId, agent.id, effectiveThread, turnId, error))
                        cancelDescendants(runId, "parent $runId failed")
                    }
                    throw failure
                }
            }
            deferred.invokeOnCompletion { cause ->
                turnReservation?.cancel()
                if (turnOwner && turnId != null) turnContexts.update { it - turnId }
                releaseRegistration(agent)
                if (cause is CancellationException) {
                    acb.markCancelled()
                    if (acb.snapshot.state == LifecycleState.CANCELLED && acb.claimCancellationEvent()) {
                        emit(RuntimeEvent.Cancelled(runId, agent.id, effectiveThread, turnId))
                    }
                }
                sink.close(cause)
            }
            completionOwnsRegistration = true
            val handle = AgentHandle(runId, agent.id, effectiveThread, turnId, acb, control, deferred)
            handles.update { it + (runId to handle) }

            if (closed.value) {
                handle.cancel("agent runtime closed")
                handles.update { it - runId }
                acbs.update { it - runId }
                ipc.remove(runId)
                error("AgentRuntime is closed")
            }
            return handle
        } finally {
            if (!completionOwnsRegistration) {
                if (childLinked) parentAcb?.removeChild(runId)
                turnReservation?.cancel()
                if (turnContextRegistered && turnId != null) turnContexts.update { it - turnId }
                releaseRegistration(agent)
            }
        }
    }

    private suspend fun runInstance(
        gate: InstanceActivityGate,
        agent: Agent,
        input: String,
        contextPrefix: List<Message>,
        acb: Acb,
        control: InstanceControl,
        priority: Int,
        quota: Quota,
        sink: EventSink,
        thread: ThreadId?,
        turnId: TurnId?,
        turnContext: TurnContext?,
        turnOwner: Boolean,
        turnReservation: ThreadRegistry.TurnReservation?,
        structuredSpec: OutputSpec?,
    ): AgentResult {
        var threadLease: ThreadRegistry.ThreadLease? = null
        var threadMemory: ThreadMemory? = null
        val buffer: TurnCommitBuffer? = if (turnOwner) turnContext?.commitBuffer else null
        var terminalUsage = Usage.ZERO
        var turnCommitted = false
        try {
            val effectiveContext = if (thread != null && turnId != null && turnContext != null) {
                val user = Message.user(input)
                val history = if (turnOwner) {
                    val lease = requireNotNull(turnReservation).await().also { threadLease = it }
                    val memory = lease.memory().also { threadMemory = it }
                    memory.load(user).also(turnContext::publishHistory)
                } else {
                    threads.joinActiveTurn(thread, turnId, agent)
                    turnContext.historySnapshot()
                }
                history + contextPrefix
            } else {
                contextPrefix
            }

            acb.markReady()
            val result = scheduler.withSlot(priority) { lease ->
                gate.attachLease(lease)
                acb.markRunning()
                emit(RuntimeEvent.Running(acb.runId, agent.id, thread, turnId, agent.name))
                runWithQuota(agent, input, effectiveContext, acb, control, quota, sink, buffer, structuredSpec) { usage ->
                    terminalUsage = usage
                }
            }
            acb.sealChildren()
            if (!acb.snapshot.state.isTerminal && hasActiveChildren(acb.runId)) {
                acb.setState(LifecycleState.WAITING)
                emit(RuntimeEvent.Waiting(acb.runId, agent.id, thread, turnId))
            }
            val childFailure = awaitChildren(acb.runId)
            if (result !is AgentResult.Failed && childFailure != null) {
                val error = AgentError.ModelError(
                    message = "child run '${childFailure.runId.value}' failed: ${childFailure.error.message}",
                    retriable = false,
                    cause = childFailure.error.cause,
                )
                acb.markFailed(error, result.usage)
                emit(RuntimeEvent.Failed(acb.runId, agent.id, thread, turnId, error))
                sink.emit(AgentEvent.Failed(error, result.usage))
                return AgentResult.Failed(error, result.usage)
            }
            when (result) {
                is AgentResult.Completed -> if (acb.beginCommitting()) {
                    withContext(NonCancellable) {
                        val commitBuffer = buffer
                        val memory = threadMemory
                        if (commitBuffer != null && memory != null && commitBuffer.shouldCommit()) {
                            memory.commit(commitBuffer.messagesInOrder(), terminalUsage)
                            turnCommitted = true
                        }
                        acb.markFinished(result.usage)
                        emit(RuntimeEvent.Finished(acb.runId, agent.id, thread, turnId, result.usage))
                    }
                }
                is AgentResult.Terminated -> if (acb.beginCommitting()) {
                    withContext(NonCancellable) {
                        val commitBuffer = buffer
                        val memory = threadMemory
                        if (commitBuffer != null && memory != null && commitBuffer.shouldCommit()) {
                            memory.commit(commitBuffer.messagesInOrder(), terminalUsage)
                            turnCommitted = true
                        }
                        acb.markFinished(result.usage)
                        emit(RuntimeEvent.Terminated(acb.runId, agent.id, thread, turnId, result.reason))
                    }
                }
                is AgentResult.Failed -> Unit
            }
            return result
        } catch (cancelled: CancellationException) {
            acb.sealChildren()
            acb.markCancelled()
            if (acb.snapshot.state == LifecycleState.CANCELLED && acb.claimCancellationEvent()) {
                emit(RuntimeEvent.Cancelled(acb.runId, agent.id, thread, turnId))
            }
            cancelDescendants(acb.runId, "parent ${acb.runId} cancelled")
            withContext(NonCancellable) { awaitChildren(acb.runId) }
            throw cancelled
        } catch (failure: Throwable) {
            acb.sealChildren()
            cancelDescendants(acb.runId, "parent ${acb.runId} failed")
            withContext(NonCancellable) { awaitChildren(acb.runId) }
            throw failure
        } finally {
            withContext(NonCancellable) {
                val lease = threadLease
                if (lease != null) {
                    if (!turnCommitted && turnContext?.hasSideEffects == true) {
                        logger.warn { "thread ${lease.threadId.value}: side-effecting turn was rolled back" }
                        emit(
                            RuntimeEvent.SideEffectRollback(
                                runId = acb.runId,
                                agentId = agent.id,
                                threadId = lease.threadId,
                                turnId = requireNotNull(turnId),
                            ),
                        )
                    }
                    lease.release()
                }
            }
        }
    }

    private suspend fun runWithQuota(
        agent: Agent,
        input: String,
        contextPrefix: List<Message>,
        acb: Acb,
        control: InstanceControl,
        quota: Quota,
        sink: EventSink,
        buffer: TurnCommitBuffer?,
        structuredSpec: OutputSpec?,
        terminalUsage: (Usage) -> Unit,
    ): AgentResult {
        val wall = quota.wallClockMillis
            ?: return collectStream(agent, input, contextPrefix, acb, control, quota, sink, buffer, structuredSpec, terminalUsage)
        return try {
            withTimeout(wall.milliseconds) {
                collectStream(agent, input, contextPrefix, acb, control, quota, sink, buffer, structuredSpec, terminalUsage)
            }
        } catch (_: TimeoutCancellationException) {
            val error = AgentError.Timeout("agent wall-clock", wall)
            val usage = acb.snapshot.usage
            acb.markFailed(error, usage)
            emit(RuntimeEvent.Failed(acb.runId, acb.snapshot.agentId, acb.snapshot.threadId, acb.snapshot.turnId, error))
            cancelDescendants(acb.runId, "parent ${acb.runId} timed out")
            sink.emit(AgentEvent.Failed(error, usage))
            AgentResult.Failed(error, usage)
        }
    }

    private suspend fun collectStream(
        agent: Agent,
        input: String,
        contextPrefix: List<Message>,
        acb: Acb,
        control: InstanceControl,
        quota: Quota,
        sink: EventSink,
        buffer: TurnCommitBuffer?,
        structuredSpec: OutputSpec?,
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
                agent.executeStream(input, contextPrefix)
            } else {
                agent.executeStructuredStream(input, contextPrefix, structuredSpec)
            }
            events.collect { event ->
                buffer?.observe(event)
                if (control.isPaused) {
                    acb.setState(LifecycleState.SUSPENDED)
                    emit(RuntimeEvent.Suspended(acb.runId, agent.id, acb.snapshot.threadId, acb.snapshot.turnId))
                    control.awaitResumed()
                    acb.markRunning()
                    emit(RuntimeEvent.Resumed(acb.runId, agent.id, acb.snapshot.threadId, acb.snapshot.turnId))
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
                emit(RuntimeEvent.Cancelled(acb.runId, agent.id, acb.snapshot.threadId, acb.snapshot.turnId))
            }
            cancelDescendants(acb.runId, "parent ${acb.runId} quota exceeded")
            val reason = TerminationReason.Custom("quota exceeded: ${quotaSignal.dimension}")
            val message = Message.assistant(stepText.toString().ifEmpty { lastAssistant })
            sink.emit(AgentEvent.Terminated(message, currentUsage, reason))
            return AgentResult.Terminated(message, currentUsage, reason)
        }

        return when (val term = terminal) {
            is AgentEvent.Completed -> {
                AgentResult.Completed(term.message, usage)
            }
            is AgentEvent.Terminated -> {
                AgentResult.Terminated(term.message, usage, term.reason)
            }
            null -> {
                val error = lastFailure ?: AgentError.ModelError("agent produced no terminal event", retriable = false)
                acb.markFailed(error, usage)
                emit(RuntimeEvent.Failed(acb.runId, agent.id, acb.snapshot.threadId, acb.snapshot.turnId, error))
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
        contextRefs: List<ContextRef> = emptyList(),
        thread: ThreadId? = null,
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
                            turnId = current?.turnId,
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
                            turnId = current?.turnId,
                        ),
                    )
                },
            ) { attemptInput ->
                val handle = spawn(agent, attemptInput, priority, quota, contextRefs, thread)
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

    private suspend fun awaitChildren(id: RunId): ChildFailure? {
        val children = acbs.value[id]?.snapshot?.children.orEmpty()
        var firstFailure: ChildFailure? = null
        children.forEach { child ->
            val handle = handles.value[child] ?: return@forEach
            try {
                val result = handle.await()
                if (result is AgentResult.Failed && firstFailure == null) {
                    firstFailure = ChildFailure(child, result.error)
                }
            } catch (cancelled: CancellationException) {
                currentCoroutineContext().ensureActive()
                if (firstFailure == null) {
                    firstFailure = ChildFailure(
                        child,
                        AgentError.ModelError(
                            message = cancelled.message ?: "child run was cancelled",
                            retriable = false,
                            cause = cancelled,
                        ),
                    )
                }
            } catch (failure: Throwable) {
                if (firstFailure == null) {
                    firstFailure = ChildFailure(
                        child,
                        handle.snapshot.error ?: AgentError.ModelError(
                            message = failure.message ?: "child run failed unexpectedly",
                            retriable = false,
                            cause = failure,
                        ),
                    )
                }
            }
        }
        return firstFailure
    }

    private fun hasActiveChildren(id: RunId): Boolean =
        acbs.value[id]?.snapshot?.children.orEmpty().any { child -> handles.value[child]?.isActive == true }

    private suspend fun cancelAndJoin(handle: AgentHandle, reason: String) {
        handle.cancel(reason)
        withContext(NonCancellable) { handle.join() }
    }

    private fun resolveContextPrefix(refs: List<ContextRef>, requester: RunId): List<Message> =
        refs.flatMap { context.resolve(it, requester) }

    override fun close() {
        if (!closed.compareAndSet(expect = false, update = true)) return
        handles.value.values.forEach { it.cancel("agent runtime closed") }
        job.cancel(CancellationException("agent runtime closed"))
        ipc.closeAll()
        handles.value = emptyMap()
        acbs.value = emptyMap()
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

fun AgentRuntime(configure: AgentRuntimeConfig.() -> Unit = {}): AgentRuntime =
    AgentRuntime(AgentRuntimeConfig().apply(configure))
