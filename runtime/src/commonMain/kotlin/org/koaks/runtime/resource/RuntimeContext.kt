package org.koaks.runtime.resource

import kotlinx.coroutines.currentCoroutineContext
import org.koaks.framework.loop.Agent
import org.koaks.framework.loop.AgentExecutionContext
import org.koaks.runtime.acb.AgentHandle
import org.koaks.runtime.acb.AgentId
import org.koaks.runtime.context.ContextRef
import org.koaks.runtime.context.ContextStore
import org.koaks.runtime.ipc.IpcHub
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
 *
 * Slot admission and the RUNNING/WAITING transitions live in the instance's
 * [AgentExecutionContext] (the activity gate), not here: an instance has multiple
 * execution branches (root loop + parallel tool calls), so slot ownership tracks
 * per-branch activity rather than a single flag.
 */
class RuntimeContext internal constructor(
    val resources: ResourceRegistry,
    val agentId: AgentId,
    val ipc: IpcHub,
    val context: ContextStore,
    private val spawner: AgentSpawner,
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
 * Under a runtime, the current execution branch is marked waiting while acquiring the
 * lock (releasing the instance's slot if no other branch is runnable), then returns to
 * running for [block].
 */
suspend fun <T> withRuntimeResource(
    id: String,
    mode: AccessMode = AccessMode.WRITE,
    block: suspend () -> T,
): T {
    val ctx = currentRuntimeContext() ?: return block()
    val exec = currentCoroutineContext()[AgentExecutionContext] ?: return ctx.resources.withResource(id, mode, block)
    exec.enterWaiting()
    try {
        return ctx.resources.withResource(id, mode) {
            exec.leaveWaiting()
            block()
        }
    } finally {
        // If acquire was cancelled (still waiting) or block threw after leaveWaiting, this
        // restores runnable state; leaveWaiting is idempotent so the success path is a no-op.
        exec.leaveWaiting()
    }
}
