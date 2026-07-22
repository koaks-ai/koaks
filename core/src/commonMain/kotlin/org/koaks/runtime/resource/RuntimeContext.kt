package org.koaks.runtime.resource

import kotlinx.coroutines.currentCoroutineContext
import org.koaks.framework.loop.Agent
import org.koaks.framework.loop.AgentExecutionContext
import org.koaks.framework.loop.AgentId
import org.koaks.framework.memory.ThreadId
import org.koaks.runtime.acb.AgentHandle
import org.koaks.runtime.acb.RunId
import org.koaks.runtime.acb.TurnId
import org.koaks.runtime.context.ContextRef
import org.koaks.runtime.context.ContextStore
import org.koaks.runtime.ipc.IpcHub
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Spawns a child instance on the same runtime. Bound into [RuntimeContext] so tools can
 * create children without holding the full [org.koaks.runtime.AgentRuntime] (no close /
 * reap / metrics). The current instance is always recorded as the child's parent; when
 * the parent belongs to a Thread, the child inherits the same ThreadId and TurnId.
 */
fun interface AgentSpawner {
    fun spawn(
        agent: Agent,
        input: String,
        priority: Int,
        quota: Quota?,
        contextRefs: List<ContextRef>,
        thread: ThreadId?,
    ): AgentHandle
}

/**
 * A [CoroutineContext] element the runtime attaches to every spawned instance's coroutine.
 * Tools reach [ResourceRegistry], [IpcHub], [ContextStore], and a restricted [spawn] via
 * [coroutineContext]. Public `Agent.run/stream/spawn` calls install this context via the
 * process-wide default Runtime.
 *
 * Slot admission and the RUNNING/WAITING transitions live in the instance's
 * [AgentExecutionContext] (the activity gate), not here: an instance has multiple
 * execution branches (root loop + parallel tool calls), so slot ownership tracks
 * per-branch activity rather than a single flag.
 */
class RuntimeContext internal constructor(
    val resources: ResourceRegistry,
    val runId: RunId,
    val agentId: AgentId,
    val threadId: ThreadId?,
    val turnId: TurnId?,
    val ipc: IpcHub,
    val context: ContextStore,
    private val spawner: AgentSpawner,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RuntimeContext>

    /**
     * Spawns [agent] as a **child** of this instance (parent = [runId]). Restricted:
     * cannot close the runtime or mutate unrelated instances. By default the child joins
     * this instance's Turn; an explicit different [thread] creates a new queued Turn. The
     * parent cannot finish until the child tree settles.
     */
    fun spawn(
        agent: Agent,
        input: String,
        priority: Int = 0,
        quota: Quota? = null,
        contextRefs: List<ContextRef> = emptyList(),
        thread: ThreadId? = null,
    ): AgentHandle = spawner.spawn(agent, input, priority, quota, contextRefs, thread)

    fun spawn(
        agent: Agent,
        input: String,
        thread: String,
        priority: Int = 0,
        quota: Quota? = null,
        contextRefs: List<ContextRef> = emptyList(),
    ): AgentHandle = spawn(agent, input, priority, quota, contextRefs, ThreadId(thread))
}

/** The current runtime context, or `null` when called outside runtime-managed execution. */
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
    thread: ThreadId? = null,
): AgentHandle {
    val ctx = currentRuntimeContext()
        ?: error("spawnChild is only available inside a runtime-spawned agent")
    return ctx.spawn(agent, input, priority, quota, contextRefs, thread)
}

suspend fun spawnChild(
    agent: Agent,
    input: String,
    thread: String,
    priority: Int = 0,
    quota: Quota? = null,
    contextRefs: List<ContextRef> = emptyList(),
): AgentHandle = spawnChild(agent, input, priority, quota, contextRefs, ThreadId(thread))

/**
 * Runs [block] holding the runtime lock for shared resource [id]. When there is no
 * runtime in scope (for example when used as a standalone helper), it runs [block] directly — private,
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
