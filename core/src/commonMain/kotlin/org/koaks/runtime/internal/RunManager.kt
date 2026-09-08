package org.koaks.runtime.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import org.koaks.framework.loop.Agent
import org.koaks.framework.loop.AgentId
import org.koaks.framework.loop.AgentResult
import org.koaks.framework.loop.OutputSpec
import org.koaks.framework.memory.ThreadId
import org.koaks.framework.model.AgentError
import org.koaks.framework.model.EventDetail
import org.koaks.framework.model.ModelItem
import org.koaks.runtime.AgentIdConflictException
import org.koaks.runtime.AgentRuntime
import org.koaks.runtime.AgentRuntimeConfig
import org.koaks.runtime.conversation.ConversationRegistry
import org.koaks.runtime.conversation.ConversationSnapshot
import org.koaks.runtime.coordination.AgentContext
import org.koaks.runtime.coordination.AgentSpawner
import org.koaks.runtime.coordination.CoordinationFacade
import org.koaks.runtime.observability.RuntimeEvent
import org.koaks.runtime.observability.RuntimeMetrics
import org.koaks.runtime.policy.ChildConversation
import org.koaks.runtime.policy.ChildFailurePolicy
import org.koaks.runtime.policy.Quota
import org.koaks.runtime.run.RunEventPayload
import org.koaks.runtime.run.RunHandle
import org.koaks.runtime.run.RunId
import org.koaks.runtime.run.RunSnapshot
import org.koaks.runtime.run.RunState

/** Creates and registers runs and owns the runtime's coroutine and service lifetime. */
internal class RunManager(config: AgentRuntimeConfig) : AutoCloseable {
    val maxConcurrency: Int = config.maxConcurrency
    private val defaultQuota: Quota = config.defaultQuota
    private val runEventBufferCapacity: Int = config.runEventBufferCapacity.also {
        require(it > 0) { "runEventBufferCapacity must be positive" }
    }

    private val job = SupervisorJob()
    val scope = CoroutineScope(config.dispatcher + job + CoroutineName("agent-runtime"))
    private val scheduler = ConcurrencyScheduler(config.maxConcurrency)
    private val threads = ConversationRegistry(config.defaultMemoryProvider)

    private val _events = MutableSharedFlow<RuntimeEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<RuntimeEvent> get() = _events.asSharedFlow()

    fun emit(event: RuntimeEvent) {
        event.runIdOrNull()?.let { id ->
            eventJournals.value[id]?.append(RunEventPayload.Lifecycle(event))
        }
        _events.tryEmit(event)
    }

    val coordination: CoordinationFacade = CoordinationFacade()

    private val registry = RunRegistry()
    private val records get() = registry.records
    private val handles get() = registry.handles
    private val eventJournals get() = registry.journals
    private val tree = RunTreeCoordinator(registry, ::emit)
    private val executor = RunExecutor(scheduler, threads, tree, ::emit)

    private val registrations = MutableStateFlow<Map<AgentId, AgentRegistration>>(emptyMap())
    private val closed = MutableStateFlow(false)

    init {
        job.invokeOnCompletion {
            threads.close()
        }
    }

    val runs: List<RunSnapshot> get() = records.value.values.map { it.snapshot }

    fun snapshot(id: RunId): RunSnapshot? = records.value[id]?.snapshot
    suspend fun conversationSnapshot(id: ThreadId): ConversationSnapshot? = threads.snapshot(id)

    fun metrics(): RuntimeMetrics {
        val snaps = records.value.values.map { it.snapshot }
        fun count(state: RunState) = snaps.count { it.state == state }
        return RuntimeMetrics(
            total = snaps.size,
            created = count(RunState.CREATED),
            threadQueued = count(RunState.THREAD_QUEUED),
            ready = count(RunState.READY),
            running = count(RunState.RUNNING),
            waiting = count(RunState.WAITING),
            suspended = count(RunState.SUSPENDED),
            committing = count(RunState.COMMITTING),
            finished = count(RunState.FINISHED),
            failed = count(RunState.FAILED),
            cancelled = count(RunState.CANCELLED),
            totalTokens = snaps.sumOf { it.usage.totalTokens },
            totalSteps = snaps.sumOf { it.stepsCompleted },
            totalToolCalls = snaps.sumOf { it.toolCalls },
        )
    }

    fun reap(olderThanMillis: Long = 0L): Int {
        val victims = records.value.filter { (_, record) ->
            val snap = record.snapshot
            snap.state.isTerminal && (olderThanMillis <= 0L || snap.elapsedMillis >= olderThanMillis)
        }.keys
        if (victims.isEmpty()) return 0
        victims.forEach { coordination.remove(it) }
        records.update { it - victims }
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

    fun spawn(
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
    ): RunHandle {
        check(!closed.value) { "AgentRuntime is closed" }
        require(correlationId == null || correlationId.isNotBlank()) { "correlationId must not be blank" }

        val contextSnapshot = context.toList()
        val parentRecord = parent?.let { parentId ->
            records.value[parentId]
                ?: error("parent run '${parentId.value}' does not exist in this runtime")
        }
        val parentSnapshot = parentRecord?.snapshot
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

        val runId = registry.nextId()
        acquireRegistration(agent)
        var completionOwnsRegistration = false
        var runReservation: ConversationRegistry.RunReservation? = null
        var childLinked = false
        try {
            runReservation = if (runOwner) {
                threads.reserve(effectiveThread!!, runId, agent)
            } else {
                null
            }
            if (parentRecord != null) {
                check(parentRecord.tryAddChild(runId, failurePolicy)) {
                    "parent run '${parentRecord.runId.value}' is no longer accepting children"
                }
                childLinked = true
            }
            val effectiveCorrelationId = correlationId ?: parentSnapshot?.correlationId
            val record = RunRecord(runId, agent.id, agent.name, effectiveThread, priority, parent, effectiveCorrelationId)
            if (runReservation != null) record.markThreadQueued()
            records.update { it + (runId to record) }
            val eventJournal = RunJournal(
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
            val control = RunControl()
            coordination.inbox(runId)

            val childSpawner = AgentSpawner {
                    childAgent, childInput, childPriority, childQuota, childRefs,
                    childFailurePolicy, conversation ->
                spawn(
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
            val runtimeContext = AgentContext(
                runId,
                agent.id,
                effectiveThread,
                effectiveCorrelationId,
                coordination,
                childSpawner,
            )
            val gate = InstanceActivityGate(runId, record, ::emit)

            val deferred: Deferred<AgentResult> = scope.async(runtimeContext + gate) {
                try {
                    val refs = contextSnapshot
                    executor.execute(
                        gate,
                        agent,
                        input,
                        refs,
                        record,
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
                    val snapshot = record.snapshot
                    if (!snapshot.state.isTerminal) {
                        val error = AgentError.ModelError(
                            message = failure.message ?: "runtime instance failed unexpectedly",
                            retriable = false,
                            cause = failure,
                        )
                        record.markFailed(error, snapshot.usage)
                        emit(RuntimeEvent.Failed(runId, agent.id, effectiveThread, error))
                        tree.cancelDescendants(runId, "parent $runId failed")
                    }
                    throw failure
                }
            }
            deferred.invokeOnCompletion { cause ->
                runReservation?.cancel()
                releaseRegistration(agent)
                if (cause is CancellationException) {
                    record.markCancelled()
                    if (record.snapshot.state == RunState.CANCELLED && record.claimCancellationEvent()) {
                        emit(RuntimeEvent.Cancelled(runId, agent.id, effectiveThread))
                    }
                }
                sink.close(cause)
            }
            completionOwnsRegistration = true
            val handle = RunHandle(
                runId,
                agent.id,
                effectiveThread,
                effectiveCorrelationId,
                record,
                control,
                deferred,
                failurePolicy,
                eventJournal,
            )
            handles.update { it + (runId to handle) }

            if (closed.value) {
                handle.cancel("agent runtime closed")
                handles.update { it - runId }
                records.update { it - runId }
                eventJournals.update { it - runId }
                coordination.remove(runId)
                error("AgentRuntime is closed")
            }
            return handle
        } finally {
            if (!completionOwnsRegistration) {
                if (childLinked) parentRecord?.removeChild(runId)
                runReservation?.cancel()
                eventJournals.update { it - runId }
                releaseRegistration(agent)
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(expect = false, update = true)) return
        handles.value.values.forEach { it.cancel("agent runtime closed") }
        job.cancel(CancellationException("agent runtime closed"))
        coordination.close()
        handles.value = emptyMap()
        records.value = emptyMap()
        eventJournals.value = emptyMap()
        registrations.value = emptyMap()
    }
}

private data class AgentRegistration(
    val definitionUid: Long,
    val agentName: String,
    val activeRuns: Int,
    val definitionVersion: Long,
)

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
