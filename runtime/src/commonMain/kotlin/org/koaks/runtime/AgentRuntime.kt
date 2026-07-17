package org.koaks.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
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
import org.koaks.framework.loop.AgentResult
import org.koaks.framework.model.AgentError
import org.koaks.framework.model.Message
import org.koaks.framework.model.Usage
import org.koaks.framework.policy.TerminationReason
import org.koaks.runtime.acb.Acb
import org.koaks.runtime.acb.AcbSnapshot
import org.koaks.runtime.acb.AgentHandle
import org.koaks.runtime.acb.AgentId
import org.koaks.runtime.acb.ChannelEventSink
import org.koaks.runtime.acb.EventSink
import org.koaks.runtime.acb.InstanceControl
import org.koaks.runtime.acb.LifecycleState
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
import kotlin.time.Duration.Companion.milliseconds

/**
 * Configuration for an [AgentRuntime]. Mutated only inside the `AgentRuntime { }`
 * builder; the runtime reads an effective snapshot at construction.
 */
class AgentRuntimeConfig {
    /** Upper bound on concurrently RUNNING instances. */
    var maxConcurrency: Int = Int.MAX_VALUE

    /** The dispatcher instances run on. */
    var dispatcher: CoroutineDispatcher = Dispatchers.Default

    /** Quota applied to spawns that don't pass their own. Build one with `quota { }`. */
    var defaultQuota: Quota = Quota.UNLIMITED
}

/**
 * A cooperative, pure-Kotlin "multi-agent OS kernel". It manages agent run instances
 * the way an OS manages processes: each [spawn] creates an [Acb], admits it through the
 * [Scheduler] (priority + concurrency cap), launches the run as a coroutine, and tracks
 * its lifecycle by collecting the agent's outward [AgentEvent] stream while enforcing a
 * [Quota].
 *
 * This is an **explicit, scoped instance** — never a hidden global. You create it, hold
 * it, and [close] it (or use [withAgentRuntime]). The simple direct path
 * ([Agent.run]/[Agent.stream]) keeps working untouched.
 */
class AgentRuntime internal constructor(config: AgentRuntimeConfig) : AutoCloseable {

    val maxConcurrency: Int = config.maxConcurrency
    private val defaultQuota: Quota = config.defaultQuota

    private val job = SupervisorJob()
    private val scope = CoroutineScope(config.dispatcher + job + CoroutineName("agent-runtime"))
    private val scheduler = Scheduler(config.maxConcurrency)
    private val supervisor = Supervisor()

    private val _events = MutableSharedFlow<RuntimeEvent>(extraBufferCapacity = 256)

    /** The runtime observability stream (system-monitor data plane). */
    val events: SharedFlow<RuntimeEvent> get() = _events.asSharedFlow()

    private fun emit(event: RuntimeEvent) {
        _events.tryEmit(event)
    }

    /** Mediates access to shared, contended resources across instances. */
    val resources: ResourceRegistry = ResourceRegistry()

    /** Inter-agent communication: mailboxes, pub/sub, request/response. */
    val ipc: IpcHub = IpcHub()

    /** Content-addressed, copy-on-write context storage shared across instances. */
    val context: ContextStore = ContextStore()

    private val idSeq = MutableStateFlow(0L)
    private val acbs = MutableStateFlow<Map<AgentId, Acb>>(emptyMap())
    private val handles = MutableStateFlow<Map<AgentId, AgentHandle>>(emptyMap())
    private val closed = MutableStateFlow(false)

    /** Snapshots of all instances the runtime currently knows about. */
    val agents: List<AcbSnapshot> get() = acbs.value.values.map { it.snapshot }

    /** The control-block snapshot for [id], or `null` if unknown (or already [reap]ed). */
    fun snapshot(id: AgentId): AcbSnapshot? = acbs.value[id]?.snapshot

    /** An aggregate snapshot of the runtime, computed on demand from the ACB table. */
    fun metrics(): RuntimeMetrics {
        val snaps = acbs.value.values.map { it.snapshot }
        fun count(state: LifecycleState) = snaps.count { it.state == state }
        return RuntimeMetrics(
            total = snaps.size,
            created = count(LifecycleState.CREATED),
            ready = count(LifecycleState.READY),
            running = count(LifecycleState.RUNNING),
            waiting = count(LifecycleState.WAITING),
            suspended = count(LifecycleState.SUSPENDED),
            finished = count(LifecycleState.FINISHED),
            failed = count(LifecycleState.FAILED),
            cancelled = count(LifecycleState.CANCELLED),
            totalTokens = snaps.sumOf { it.usage.totalTokens },
            totalSteps = snaps.sumOf { it.stepsCompleted },
            totalToolCalls = snaps.sumOf { it.toolCalls },
        )
    }

    /**
     * Removes terminal ACBs ([FINISHED] / [FAILED] / [CANCELLED]) from the registry and
     * closes their mailboxes. [AgentHandle]s already returned to callers keep working
     * (they hold the [Acb] directly); only [agents] / [snapshot] / [metrics] forget them.
     *
     * @param olderThanMillis when `> 0`, only reap instances whose [AcbSnapshot.elapsedMillis]
     *   is at least this old; `0` reaps every terminal instance.
     * @return number of reaped instances
     */
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

    /** Cancels an instance's descendants (parent -> child fault propagation). */
    private fun cancelDescendants(id: AgentId, reason: String) {
        val acb = acbs.value[id] ?: return
        acb.snapshot.children.forEach { child ->
            handles.value[child]?.cancel(reason)
            cancelDescendants(child, reason)
        }
    }

    /** Runs [agent] as a runtime-managed foreground instance and returns its terminal result. */
    suspend fun run(
        agent: Agent,
        input: String,
        priority: Int = 0,
        quota: Quota? = null,
        parent: AgentId? = null,
        contextRefs: List<ContextRef> = emptyList(),
        thread: String? = null,
    ): AgentResult {
        val handle = spawn(agent, input, priority, quota, parent, contextRefs, thread)
        return try {
            handle.await()
        } catch (c: CancellationException) {
            // `spawn` belongs to the runtime scope, so awaiting it is not enough to propagate
            // caller cancellation. Foreground `run` owns the instance and must cancel it.
            cancelAndJoin(handle, c.message ?: "runtime run cancelled")
            throw c
        }
    }

    /**
     * Streams one runtime-managed foreground execution. The returned flow is cold: every
     * collection creates a fresh instance. Downstream backpressure reaches the agent, and
     * stopping collection cooperatively cancels the instance instead of leaving background
     * work behind. A slow collector pauses the agent while it still owns its scheduler slot;
     * callers that need decoupling should add an explicit bounded `buffer` downstream.
     */
    fun stream(
        agent: Agent,
        input: String,
        priority: Int = 0,
        quota: Quota? = null,
        parent: AgentId? = null,
        contextRefs: List<ContextRef> = emptyList(),
        thread: String? = null,
    ): Flow<AgentEvent> = channelFlow {
        val handle = spawnInternal(
            agent = agent,
            input = input,
            priority = priority,
            quota = quota,
            parent = parent,
            contextRefs = contextRefs,
            sink = ChannelEventSink(channel),
            thread = thread,
        )
        try {
            handle.join()
        } finally {
            // A failed channel send can race the collector's own cancellation and complete the
            // deferred before this finally runs. A non-terminal ACB still requires cancellation
            // cleanup even when Deferred.isActive has already flipped to false.
            if (handle.isActive || !handle.state.isTerminal) {
                cancelAndJoin(handle, "runtime stream collection stopped")
            }
        }
    }.buffer(Channel.RENDEZVOUS)

    /**
     * Spawns [agent] as a runtime-managed background instance and returns its [AgentHandle].
     * The agent is passed by value (the runtime never owns or registers agents), so the same
     * immutable agent can be spawned repeatedly, each time getting a fresh ACB.
     *
     * @param quota per-instance quota; falls back to the runtime default when `null`.
     * @param parent optional parent instance used for lifecycle propagation and context access.
     * @param contextRefs shared context blocks resolved with [parent]'s permissions (or this
     *   instance's identity for a root run) and prepended to the initial messages.
     */
    fun spawn(
        agent: Agent,
        input: String,
        priority: Int = 0,
        quota: Quota? = null,
        parent: AgentId? = null,
        contextRefs: List<ContextRef> = emptyList(),
        thread: String? = null,
    ): AgentHandle = spawnInternal(agent, input, priority, quota, parent, contextRefs, EventSink.NONE, thread)

    private fun spawnInternal(
        agent: Agent,
        input: String,
        priority: Int,
        quota: Quota?,
        parent: AgentId?,
        contextRefs: List<ContextRef>,
        sink: EventSink,
        thread: String? = null,
    ): AgentHandle {
        check(!closed.value) { "AgentRuntime is closed" }

        val id = AgentId(idSeq.getAndUpdate { it + 1 })
        val acb = Acb(id, agent.name, priority, parent)
        acbs.update { it + (id to acb) }
        parent?.let { p -> acbs.value[p]?.addChild(id) }
        emit(RuntimeEvent.Spawned(id, agent.name, priority, parent))

        val effectiveQuota = quota ?: defaultQuota
        val control = InstanceControl()
        ipc.mailbox(id) // ensure the instance has an inbox before it can be addressed

        val childSpawner = AgentSpawner { childAgent, childInput, childPriority, childQuota, childRefs ->
            spawn(childAgent, childInput, childPriority, childQuota, parent = id, contextRefs = childRefs)
        }

        val runtimeContext = RuntimeContext(resources, id, ipc, context, childSpawner)
        // Bridges the instance's parallel execution branches (root loop + one per parallel
        // tool call) to its single scheduler slot: the slot is held while any branch is
        // runnable and parked once all branches wait. Installed as the core-neutral
        // AgentExecutionContext so AgentRunner and AgentHandle.await reach it without the
        // runtime leaking into core.
        val gate = InstanceActivityGate(id, acb, ::emit)

        val deferred: Deferred<AgentResult> = scope.async(runtimeContext + gate) {
            try {
                val prefix = resolveContextPrefix(contextRefs, requester = parent ?: id)
                runInstance(gate, agent, input, prefix, acb, control, priority, effectiveQuota, sink, thread)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                val snapshot = acb.snapshot
                if (!snapshot.state.isTerminal) {
                    val error = AgentError.ModelError(
                        message = t.message ?: "runtime instance failed unexpectedly",
                        retriable = false,
                        cause = t,
                    )
                    acb.markFailed(error, snapshot.usage)
                    emit(RuntimeEvent.Failed(id, error))
                    cancelDescendants(id, "parent $id failed")
                }
                throw t
            }
        }
        deferred.invokeOnCompletion { cause ->
            // Cancellation can win before the coroutine body reaches runInstance. Preserve a
            // terminal state for externally retained handles even on that pre-start path.
            if (cause is CancellationException && !acb.snapshot.state.isTerminal) {
                acb.markCancelled()
            }
            sink.close(cause)
        }
        val handle = AgentHandle(id, acb, control, deferred)
        handles.update { it + (id to handle) }

        // close() may race the non-suspending registration above. Do not leave a cancelled
        // handle or mailbox reinserted into a runtime that has already been torn down.
        if (closed.value) {
            handle.cancel("agent runtime closed")
            handles.update { it - id }
            acbs.update { it - id }
            ipc.remove(id)
            error("AgentRuntime is closed")
        }
        return handle
    }

    /** Cancels a foreground-owned instance and waits for its runtime coroutine to unwind. */
    private suspend fun cancelAndJoin(handle: AgentHandle, reason: String) {
        handle.cancel(reason)
        withContext(NonCancellable) { handle.join() }
    }

    /** Resolves [refs] in order into a flat message prefix for the agent loop. */
    private fun resolveContextPrefix(refs: List<ContextRef>, requester: AgentId): List<Message> {
        if (refs.isEmpty()) return emptyList()
        return refs.flatMap { context.resolve(it, requester) }
    }

    /**
     * Spawns [agent] under supervision: bounded retries with exponential backoff, an
     * optional circuit breaker, and an optional recovery hook — a fault-tolerant restart
     * loop. The returned handle awaits the final result across attempts.
     */
    fun spawnSupervised(
        agent: Agent,
        input: String,
        policy: SupervisionPolicy = SupervisionPolicy(),
        priority: Int = 0,
        quota: Quota? = null,
        parent: AgentId? = null,
        contextRefs: List<ContextRef> = emptyList(),
        thread: String? = null,
    ): SupervisedHandle {
        val latest = MutableStateFlow<AgentHandle?>(null)
        val deferred: Deferred<AgentResult> = scope.async {
            supervisor.run(agent.name, input, policy, ::emit) { attemptInput ->
                val handle = spawn(agent, attemptInput, priority, quota, parent, contextRefs, thread)
                latest.value = handle
                handle.await()
            }
        }
        return SupervisedHandle(deferred) { reason -> latest.value?.cancel(reason) }
    }

    /**
     * Submits a task DAG. Independent nodes run concurrently (subject to the scheduler's
     * concurrency cap); a node starts only once all its dependencies have finished, and
     * can read their results to build its own input. Returns the per-node results.
     */
    suspend fun submit(graph: TaskGraph): Map<String, AgentResult> = coroutineScope {
        val results = graph.nodes.associate { it.id to CompletableDeferred<AgentResult>() }
        graph.nodes.forEach { node ->
            launch {
                try {
                    val deps = node.dependsOn.associateWith { results.getValue(it).await() }
                    val input = node.input(deps)
                    // submit is a structured foreground API: cancellation of the graph must also
                    // cancel each runtime-scoped node instance rather than only its awaiter.
                    val result = run(node.agent, input, node.priority)
                    results.getValue(node.id).complete(result)
                } catch (t: Throwable) {
                    results.getValue(node.id).completeExceptionally(t)
                    throw t
                }
            }
        }
        results.mapValues { it.value.await() }
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
        thread: String? = null,
    ): AgentResult {
        acb.markReady()
        return try {
            scheduler.withSlot(priority) { lease ->
                // Bind the admission slot so the activity gate can park it once every
                // branch of this instance is waiting.
                gate.attachLease(lease)
                acb.markRunning()
                emit(RuntimeEvent.Running(acb.id, agent.name))
                runWithQuota(agent, input, contextPrefix, acb, control, quota, sink, thread)
            }
        } catch (c: CancellationException) {
            acb.markCancelled()
            emit(RuntimeEvent.Cancelled(acb.id))
            cancelDescendants(acb.id, "parent ${acb.id} cancelled")
            throw c
        }
    }

    /** Applies the wall-clock dimension of the quota around the event collection. */
    private suspend fun runWithQuota(
        agent: Agent,
        input: String,
        contextPrefix: List<Message>,
        acb: Acb,
        control: InstanceControl,
        quota: Quota,
        sink: EventSink,
        thread: String? = null,
    ): AgentResult {
        val wall = quota.wallClockMillis
            ?: return collectStream(agent, input, contextPrefix, acb, control, quota, sink, thread)
        return try {
            withTimeout(wall.milliseconds) { collectStream(agent, input, contextPrefix, acb, control, quota, sink, thread) }
        } catch (_: TimeoutCancellationException) {
            val error = AgentError.Timeout("agent wall-clock", wall)
            val usage = acb.snapshot.usage
            acb.markFailed(error, usage)
            emit(RuntimeEvent.Failed(acb.id, error))
            cancelDescendants(acb.id, "parent ${acb.id} timed out")
            sink.emit(AgentEvent.Failed(error, usage))
            AgentResult.Failed(error, usage)
        }
    }

    /** Collects the agent event stream, updating the ACB and enforcing step/tool quotas. */
    private suspend fun collectStream(
        agent: Agent,
        input: String,
        contextPrefix: List<Message>,
        acb: Acb,
        control: InstanceControl,
        quota: Quota,
        sink: EventSink,
        thread: String? = null,
    ): AgentResult {
        var terminal: AgentEvent.Terminal? = null
        var lastFailure: AgentError? = null
        var usage = Usage.ZERO
        var steps = 0
        var toolCalls = 0
        val stepText = StringBuilder()
        var lastAssistant = ""

        try {
            agent.stream(input, thread = thread, context = contextPrefix).collect { event ->
                if (control.isPaused) {
                    acb.setState(LifecycleState.SUSPENDED)
                    emit(RuntimeEvent.Suspended(acb.id))
                    control.awaitResumed()
                    acb.markRunning()
                    emit(RuntimeEvent.Resumed(acb.id))
                }

                sink.emit(event)
                acb.observe(event)
                when (event) {
                    is AgentEvent.TextDelta -> stepText.append(event.text)
                    is AgentEvent.ToolCallRequested -> {
                        toolCalls++
                        // "max N tool calls": stop when the (N+1)th is requested.
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
                    }
                    is AgentEvent.Failed -> {
                        lastFailure = event.error
                        if (event.usage != Usage.ZERO) usage = event.usage
                    }
                    else -> {}
                }
            }
        } catch (q: QuotaSignal) {
            // Quota is a cooperative cancel (cgroup kill), not a natural finish.
            val u = acb.snapshot.usage
            acb.markCancelled()
            emit(RuntimeEvent.Cancelled(acb.id))
            cancelDescendants(acb.id, "parent ${acb.id} quota exceeded")
            val reason = TerminationReason.Custom("quota exceeded: ${q.dimension}")
            val currentText = stepText.toString()
            val message = Message.assistant(currentText.ifEmpty { lastAssistant })
            sink.emit(AgentEvent.Terminated(message, u, reason))
            return AgentResult.Terminated(message, u, reason)
        }

        return when (val term = terminal) {
            is AgentEvent.Completed -> {
                acb.markFinished(usage)
                emit(RuntimeEvent.Finished(acb.id, usage))
                AgentResult.Completed(term.message, usage)
            }
            is AgentEvent.Terminated -> {
                acb.markFinished(usage)
                emit(RuntimeEvent.Terminated(acb.id, term.reason))
                AgentResult.Terminated(term.message, usage, term.reason)
            }
            null -> {
                val error = lastFailure
                    ?: AgentError.ModelError("agent produced no terminal event", retriable = false)
                acb.markFailed(error, usage)
                emit(RuntimeEvent.Failed(acb.id, error))
                cancelDescendants(acb.id, "parent ${acb.id} failed")
                AgentResult.Failed(error, usage)
            }
        }
    }

    /** Cancels all running instances and tears down the runtime scope. */
    override fun close() {
        if (!closed.compareAndSet(expect = false, update = true)) return
        handles.value.values.forEach { it.cancel("agent runtime closed") }
        job.cancel(CancellationException("agent runtime closed"))
        ipc.closeAll()
        handles.value = emptyMap()
        acbs.value = emptyMap()
    }
}

/**
 * A quota-preemption signal. It subclasses [CancellationException] on purpose: the core
 * agent loop rethrows cancellation unchanged (it never routes it through error recovery),
 * so this propagates cleanly out of `stream().collect` where a plain exception would be
 * intercepted by the loop's model-step error handling.
 */
private class QuotaSignal(val dimension: String) : CancellationException("quota exceeded: $dimension")

/** Fake constructor: `AgentRuntime { maxConcurrency = 8 }`. */
fun AgentRuntime(configure: AgentRuntimeConfig.() -> Unit = {}): AgentRuntime =
    AgentRuntime(AgentRuntimeConfig().apply(configure))
