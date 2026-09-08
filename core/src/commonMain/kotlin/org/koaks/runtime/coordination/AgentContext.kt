package org.koaks.runtime.coordination

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import org.koaks.framework.loop.Agent
import org.koaks.framework.loop.AgentExecutionContext
import org.koaks.framework.loop.AgentId
import org.koaks.framework.memory.ThreadId
import org.koaks.framework.model.ModelItem
import org.koaks.runtime.policy.ChildConversation
import org.koaks.runtime.policy.ChildFailurePolicy
import org.koaks.runtime.policy.Quota
import org.koaks.runtime.run.RunHandle
import org.koaks.runtime.run.RunId

/**
 * Spawns a child instance on the same runtime. Bound into [AgentContext] so tools can
 * create children without holding the full [org.koaks.runtime.AgentRuntime] (no close /
 * reap / metrics). The current instance is always recorded as the child's parent; when
 * the parent belongs to a Thread, the child inherits the same ThreadId.
 */
internal fun interface AgentSpawner {
    fun spawn(
        agent: Agent,
        input: String,
        priority: Int,
        quota: Quota?,
        context: List<ModelItem>,
        failurePolicy: ChildFailurePolicy,
        conversation: ChildConversation,
    ): RunHandle
}

/**
 * A [CoroutineContext] element the runtime attaches to every spawned instance's coroutine.
 * Tools use [send], [receive], [request], [withResource] and [spawn] through this context.
 * Public `Agent.run/stream/spawn` calls install it via the process-wide default Runtime.
 *
 * Slot admission and the RUNNING/WAITING transitions live in the instance's
 * [AgentExecutionContext] (the activity gate), not here: an instance has multiple
 * execution branches (root loop + parallel tool calls), so slot ownership tracks
 * per-branch activity rather than a single flag.
 */
class AgentContext internal constructor(
    val runId: RunId,
    val agentId: AgentId,
    val threadId: ThreadId?,
    val correlationId: String?,
    private val coordination: CoordinationFacade,
    private val spawner: AgentSpawner,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<AgentContext>

    /**
     * Spawns [agent] as a **child** of this instance (parent = [runId]). Restricted:
     * cannot close the runtime or mutate unrelated instances. By default the child joins
     * this instance's Turn; [ChildConversation.Ephemeral] runs without a Thread binding,
     * while [ChildConversation.Thread] starts an independent Turn. The parent cannot finish
     * until the child tree settles.
     */
    fun spawn(
        agent: Agent,
        input: String,
        priority: Int = 0,
        quota: Quota? = null,
        context: List<ModelItem> = emptyList(),
        failurePolicy: ChildFailurePolicy = ChildFailurePolicy.PROPAGATE,
        conversation: ChildConversation = ChildConversation.Inherit,
    ): RunHandle = spawner.spawn(agent, input, priority, quota, context, failurePolicy, conversation)

    /** Sends a message using this run's identity. */
    suspend fun send(
        to: RunId,
        type: String,
        payload: String = "",
        context: List<ModelItem> = emptyList(),
        priority: Int = 0,
        deadlineMillis: Long? = null,
    ) {
        coordination.send(MessageEnvelope(coordination.nextId(), runId, to, type, payload, context, priority, deadlineMillis))
    }

    /** Receives from this run's inbox, releasing admission while every branch is waiting. */
    suspend fun receive(): MessageEnvelope = waiting { coordination.inbox(runId).receive() }

    /** Awaits a reply; use coroutine timeout/cancellation to bound the wait. */
    suspend fun request(
        to: RunId,
        type: String,
        payload: String = "",
        context: List<ModelItem> = emptyList(),
        priority: Int = 0,
        deadlineMillis: Long? = null,
    ): MessageEnvelope = waiting {
        coordination.request(MessageEnvelope(coordination.nextId(), runId, to, type, payload, context, priority, deadlineMillis))
    }

    /** Replies to the correlation carried by a received request. */
    suspend fun reply(to: MessageEnvelope, payload: String, context: List<ModelItem> = emptyList()) =
        coordination.reply(to, payload, context)

    /** Publishes without replay; priority and deadline are message metadata. */
    suspend fun publish(
        topic: String,
        type: String,
        payload: String = "",
        context: List<ModelItem> = emptyList(),
        priority: Int = 0,
        deadlineMillis: Long? = null,
    ) {
        coordination.publish(topic, MessageEnvelope(coordination.nextId(), runId, null, type, payload, context, priority, deadlineMillis))
    }

    fun subscribe(topic: String): Flow<MessageEnvelope> = coordination.subscribe(topic)

    suspend fun <T> withResource(id: String, mode: AccessMode = AccessMode.WRITE, block: suspend () -> T): T {
        val exec = currentCoroutineContext()[AgentExecutionContext]
            ?: return coordination.withResource(id, mode, block)
        exec.enterWaiting()
        try {
            return coordination.withResource(id, mode) {
                exec.leaveWaiting()
                block()
            }
        } finally {
            exec.leaveWaiting()
        }
    }

    private suspend fun <T> waiting(block: suspend () -> T): T {
        val exec = currentCoroutineContext()[AgentExecutionContext] ?: return block()
        return exec.waiting(block)
    }

}

/** The current runtime context, or `null` when called outside runtime-managed execution. */
suspend fun currentAgentContext(): AgentContext? = currentCoroutineContext()[AgentContext]

/**
 * Spawns a child agent from inside a runtime-spawned instance (tool / coroutine).
 * Fails if called outside a runtime.
 */
suspend fun spawnChild(
    agent: Agent,
    input: String,
    priority: Int = 0,
    quota: Quota? = null,
    context: List<ModelItem> = emptyList(),
    failurePolicy: ChildFailurePolicy = ChildFailurePolicy.PROPAGATE,
    conversation: ChildConversation = ChildConversation.Inherit,
): RunHandle {
    val ctx = currentAgentContext()
        ?: error("spawnChild is only available inside a runtime-spawned agent")
    return ctx.spawn(agent, input, priority, quota, context, failurePolicy, conversation)
}

/**
 * Runs [block] holding the runtime lock for shared resource [id]. When there is no
 * runtime in scope (for example when used as a standalone helper), it runs [block] directly — private,
 * uncontended IO is never forced through the mediator.
 *
 * Under a runtime, the current execution branch is marked waiting while acquiring the
 * lock (releasing the instance's slot if no other branch is runnable), then returns to
 * running for [block].
 */
suspend fun <T> withSharedResource(
    id: String,
    mode: AccessMode = AccessMode.WRITE,
    block: suspend () -> T,
): T {
    val ctx = currentAgentContext() ?: return block()
    return ctx.withResource(id, mode, block)
}
