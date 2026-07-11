package org.koaks.runtime.ipc

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import org.koaks.runtime.acb.AgentId
import org.koaks.runtime.context.ContextRef

/**
 * The runtime's IPC switchboard: owns per-instance [Mailbox]es, the pub/sub [EventBus],
 * and request/response correlation. Message ids come from here so callers don't invent
 * their own.
 */
class IpcHub internal constructor() {
    private val idSeq = MutableStateFlow(0L)
    private val mailboxes = MutableStateFlow<Map<AgentId, Mailbox>>(emptyMap())
    private val pending = MutableStateFlow<Map<Long, CompletableDeferred<RuntimeMessage>>>(emptyMap())
    private val bus = EventBus()

    /** A fresh, runtime-unique message id. */
    fun nextId(): Long = idSeq.getAndUpdate { it + 1 }

    /** The mailbox for [id], creating it on first access. */
    fun mailbox(id: AgentId): Mailbox {
        mailboxes.value[id]?.let { return it }
        val created = Mailbox(id)
        var result = created
        mailboxes.update { cur ->
            val existing = cur[id]
            if (existing != null) {
                result = existing
                cur
            } else {
                cur + (id to created)
            }
        }
        return result
    }

    /** Point-to-point send to [RuntimeMessage.receiver]'s mailbox. */
    suspend fun send(message: RuntimeMessage) {
        val target = requireNotNull(message.receiver) { "send requires a receiver" }
        mailbox(target).send(message)
    }

    /**
     * Sends [message] and suspends until the receiver [reply]s. A fresh correlation id is
     * assigned; the reply is delivered through it, not through the sender's mailbox.
     * Pending entries are always removed on completion or cancellation (no leak).
     */
    suspend fun request(message: RuntimeMessage): RuntimeMessage {
        val correlation = nextId()
        val deferred = CompletableDeferred<RuntimeMessage>()
        pending.update { it + (correlation to deferred) }
        try {
            send(message.copy(correlationId = correlation))
            return deferred.await()
        } finally {
            pending.update { it - correlation }
        }
    }

    /** Completes a pending [request] identified by [to]'s correlation id. */
    suspend fun reply(to: RuntimeMessage, payload: String, contextRefs: List<ContextRef> = emptyList()) {
        val correlation = requireNotNull(to.correlationId) { "message is not a request" }
        val response = RuntimeMessage(
            id = nextId(),
            sender = to.receiver,
            receiver = to.sender,
            type = "${to.type}.reply",
            payload = payload,
            contextRefs = contextRefs,
            correlationId = correlation,
        )
        val deferred = pending.value[correlation]
        if (deferred != null && deferred.complete(response)) {
            pending.update { it - correlation }
        } else {
            // No one waiting (timed out / cancelled): fall back to the sender's mailbox.
            to.sender?.let { mailbox(it).send(response) }
        }
    }

    /** Publishes [message] to [topic] (pub/sub). */
    suspend fun publish(topic: String, message: RuntimeMessage) = bus.publish(topic, message)

    /** Subscribes to [topic]. */
    fun subscribe(topic: String): Flow<RuntimeMessage> = bus.subscribe(topic)

    internal fun remove(id: AgentId) {
        mailboxes.value[id]?.close()
        mailboxes.update { it - id }
    }

    internal fun closeAll() {
        mailboxes.value.values.forEach { it.close() }
        mailboxes.value = emptyMap()
    }
}
