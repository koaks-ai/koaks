package org.koaks.runtime.resource

import kotlinx.coroutines.currentCoroutineContext
import org.koaks.framework.loop.Agent
import org.koaks.runtime.acb.Acb
import org.koaks.runtime.acb.AgentHandle
import org.koaks.runtime.acb.AgentId
import org.koaks.runtime.acb.LifecycleState
import org.koaks.runtime.context.ContextRef
import org.koaks.runtime.context.ContextStore
import org.koaks.runtime.ipc.IpcHub
import org.koaks.runtime.observe.RuntimeEvent
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Spawns a child instance on the same runtime. Bound into [RuntimeContext] so tools can
 * create children without holding the full [org.koaks.runtime.AgentRuntime] (no close /
 * reap / metrics). The current instance is always recorded as the child's parent.
 */
fun interface AgentSpawner {
    fun spawn(
        agent: Agent,
        input: String,
        priority: Int,
        quota: Quota?,
        contextRefs: List<ContextRef>,
    ): AgentHandle
}

/**
 * A [CoroutineContext] element the runtime attaches to every spawned instance's coroutine.
 * Tools reach [ResourceRegistry], [IpcHub], [ContextStore], and a restricted [spawn] via
 * [coroutineContext] — no globals, and the direct `agent.run()` path stays runtime-free.
 */
class RuntimeContext internal constructor(
    val resources: ResourceRegistry,
    val agentId: AgentId,
    val ipc: IpcHub,
    val context: ContextStore,
    private val acb: Acb,
    private val spawner: AgentSpawner,
    private val emit: (RuntimeEvent) -> Unit,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RuntimeContext>

    /**
     * Spawns [agent] as a **child** of this instance (parent = [agentId]). Restricted:
     * cannot close the runtime or mutate unrelated instances.
     */
    fun spawn(
        agent: Agent,
        input: String,
        priority: Int = 0,
        quota: Quota? = null,
        contextRefs: List<ContextRef> = emptyList(),
    ): AgentHandle = spawner.spawn(agent, input, priority, quota, contextRefs)

    /**
     * Marks this instance [LifecycleState.WAITING] while [block] suspends (mailbox,
     * IPC reply, resource lock acquire), then returns to [LifecycleState.RUNNING]
     * unless the instance already reached a terminal state.
     */
    suspend fun <T> whileWaiting(block: suspend () -> T): T {
        enterWaiting()
        try {
            return block()
        } finally {
            leaveWaiting()
        }
    }

    internal fun enterWaiting() {
        if (acb.snapshot.state == LifecycleState.RUNNING) {
            acb.setState(LifecycleState.WAITING)
            emit(RuntimeEvent.Waiting(agentId))
        }
    }

    internal fun leaveWaiting() {
        if (acb.snapshot.state == LifecycleState.WAITING) {
            acb.markRunning()
            emit(RuntimeEvent.Running(agentId, acb.snapshot.agentName))
        }
    }
}

/** The current runtime context, or `null` when running outside a runtime (direct path). */
suspend fun currentRuntimeContext(): RuntimeContext? = currentCoroutineContext()[RuntimeContext]

/**
 * Spawns a child agent from inside a runtime-spawned instance (tool / coroutine).
 * Fails if called outside a runtime.
 */
suspend fun spawnChild(
    agent: Agent,
    input: String,
    priority: Int = 0,
    quota: Quota? = null,
    contextRefs: List<ContextRef> = emptyList(),
): AgentHandle {
    val ctx = currentRuntimeContext()
        ?: error("spawnChild is only available inside a runtime-spawned agent")
    return ctx.spawn(agent, input, priority, quota, contextRefs)
}

/**
 * Runs [block] holding the runtime lock for shared resource [id]. When there is no
 * runtime in scope (the direct `agent.run()` path), it runs [block] directly — private,
 * uncontended IO is never forced through the mediator.
 *
 * Under a runtime, the instance enters [LifecycleState.WAITING] while acquiring the
 * lock, then returns to RUNNING for [block].
 */
suspend fun <T> withRuntimeResource(
    id: String,
    mode: AccessMode = AccessMode.WRITE,
    block: suspend () -> T,
): T {
    val ctx = currentRuntimeContext() ?: return block()
    ctx.enterWaiting()
    try {
        return ctx.resources.withResource(id, mode) {
            ctx.leaveWaiting()
            block()
        }
    } catch (t: Throwable) {
        ctx.leaveWaiting()
        throw t
    }
}
