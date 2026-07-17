package org.koaks.runtime.ipc

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import org.koaks.framework.loop.AgentExecutionContext
import org.koaks.runtime.acb.AgentId
import org.koaks.runtime.context.ContextRef
import org.koaks.runtime.resource.RuntimeContext

private suspend fun runtimeCtx(): RuntimeContext =
    currentCoroutineContext()[RuntimeContext]
        ?: error("IPC is only available inside a runtime-spawned agent")

private suspend fun hub(): IpcHub = runtimeCtx().ipc

private suspend fun self(): AgentId = runtimeCtx().agentId

/** Marks the current execution branch waiting (releasing the slot if idle) while [block] blocks. */
private suspend fun <T> waiting(block: suspend () -> T): T {
    val exec = currentCoroutineContext()[AgentExecutionContext] ?: return block()
    return exec.waiting(block)
}

/** Sends a fire-and-forget message from the current instance to [to]. */
suspend fun sendMessage(
    to: AgentId,
    type: String,
    payload: String = "",
    contextRefs: List<ContextRef> = emptyList(),
    priority: Int = 0,
    deadlineMillis: Long? = null,
) {
    val hub = hub()
    hub.send(
        RuntimeMessage(
            id = hub.nextId(),
            sender = self(),
            receiver = to,
            type = type,
            payload = payload,
            contextRefs = contextRefs,
            priority = priority,
            deadlineMillis = deadlineMillis,
        ),
    )
}

/**
 * Receives the next message addressed to the current instance.
 * Marks the current execution branch [org.koaks.runtime.acb.LifecycleState.WAITING] while blocked.
 */
suspend fun receiveMessage(): RuntimeMessage {
    val ctx = runtimeCtx()
    return waiting { ctx.ipc.mailbox(ctx.agentId).receive() }
}

/**
 * Sends a request and awaits the peer's reply.
 * Marks the current execution branch [org.koaks.runtime.acb.LifecycleState.WAITING] while blocked.
 */
suspend fun requestMessage(
    to: AgentId,
    type: String,
    payload: String = "",
    contextRefs: List<ContextRef> = emptyList(),
): RuntimeMessage {
    val ctx = runtimeCtx()
    val hub = ctx.ipc
    return waiting {
        hub.request(
            RuntimeMessage(
                id = hub.nextId(),
                sender = ctx.agentId,
                receiver = to,
                type = type,
                payload = payload,
                contextRefs = contextRefs,
            ),
        )
    }
}

/** Replies to a request previously received via [receiveMessage]. */
suspend fun replyMessage(to: RuntimeMessage, payload: String, contextRefs: List<ContextRef> = emptyList()) {
    hub().reply(to, payload, contextRefs)
}

/** Publishes a message to [topic]. */
suspend fun publishMessage(topic: String, type: String, payload: String = "", contextRefs: List<ContextRef> = emptyList()) {
    val hub = hub()
    hub.publish(
        topic,
        RuntimeMessage(
            id = hub.nextId(),
            sender = self(),
            receiver = null,
            type = type,
            payload = payload,
            contextRefs = contextRefs,
        ),
    )
}

/** Subscribes to [topic] from within a runtime-spawned agent. */
suspend fun subscribeTopic(topic: String): Flow<RuntimeMessage> = hub().subscribe(topic)
