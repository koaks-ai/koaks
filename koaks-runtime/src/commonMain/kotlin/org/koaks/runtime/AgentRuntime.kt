package org.koaks.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
import org.koaks.runtime.resource.Quota
import org.koaks.runtime.resource.ResourceRegistry
import org.koaks.runtime.resource.RuntimeContext
import org.koaks.runtime.sched.Scheduler
import org.koaks.runtime.sched.TaskGraph

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

    /**
     * Spawns [agent] on [input] as a new run instance and returns its [AgentHandle].
     * The agent is passed by value (the runtime never owns or registers agents), so the
     * same immutable agent can be spawned repeatedly, each time getting a fresh ACB.
     *
     * @param quota per-instance quota; falls back to the runtime's default when `null`.
     * @param contextRefs shared context blocks resolved (with [parent]'s / null requester
     *   permissions) and prepended to the agent's initial messages — large history travels
     *   by reference id at the call site, and only expands here once.
     */
    fun spawn(
        agent: Agent,
        input: String,
        priority: Int = 0,
        quota: Quota? = null,
        parent: AgentId? = null,
        contextRefs: List<ContextRef> = emptyList(),
    ): AgentHandle {
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

        val deferred: Deferred<AgentResult> = scope.async(
            RuntimeContext(resources, id, ipc, context, acb, childSpawner, ::emit),
        ) {
            val prefix = resolveContextPrefix(contextRefs, requester = parent ?: id)
            runInstance(agent, input, prefix, acb, control, priority, effectiveQuota)
        }
        val handle = AgentHandle(id, acb, control, deferred)
        handles.update { it + (id to handle) }
        return handle
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
    ): SupervisedHandle {
        val latest = MutableStateFlow<AgentHandle?>(null)
        val deferred: Deferred<AgentResult> = scope.async {
            supervisor.run(agent.name, input, policy, ::emit) { attemptInput ->
                val handle = spawn(agent, attemptInput, priority, quota, parent, contextRefs)
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
                    val result = spawn(node.agent, input, node.priority).await()
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
        agent: Agent,
        input: String,
        contextPrefix: List<Message>,
        acb: Acb,
        control: InstanceControl,
        priority: Int,
        quota: Quota,
    ): AgentResult {
        acb.markReady()
        return try {
            scheduler.withSlot(priority) {
                acb.markRunning()
                emit(RuntimeEvent.Running(acb.id, agent.name))
                runWithQuota(agent, input, contextPrefix, acb, control, quota)
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
    ): AgentResult {
        val wall = quota.wallClockMillis ?: return collectStream(agent, input, contextPrefix, acb, control, quota)
        return try {
            withTimeout(wall) { collectStream(agent, input, contextPrefix, acb, control, quota) }
        } catch (e: TimeoutCancellationException) {
            val error = AgentError.Timeout("agent wall-clock", wall)
            val usage = acb.snapshot.usage
            acb.markFailed(error, usage)
            emit(RuntimeEvent.Failed(acb.id, error))
            cancelDescendants(acb.id, "parent ${acb.id} timed out")
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
    ): AgentResult {
        var terminal: AgentEvent.Terminal? = null
        var lastFailure: AgentError? = null
        var usage = Usage.ZERO
        var steps = 0
        var toolCalls = 0
        val stepText = StringBuilder()
        var lastAssistant = ""

        try {
            agent.stream(input, contextPrefix).collect { event ->
                if (control.isPaused) {
                    acb.setState(LifecycleState.SUSPENDED)
                    emit(RuntimeEvent.Suspended(acb.id))
                    control.awaitResumed()
                    acb.markRunning()
                    emit(RuntimeEvent.Resumed(acb.id))
                }

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
            return AgentResult.Terminated(Message.assistant(lastAssistant), u, reason)
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
        ipc.closeAll()
        handles.value = emptyMap()
        acbs.value = emptyMap()
        job.cancel()
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
