package org.koaks.runtime.coordination

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.koaks.framework.model.ModelItem
import org.koaks.runtime.internal.SharedResourceCoordinator
import org.koaks.runtime.run.RunId

/** Runtime-local messaging and shared resource access for application code. */
class CoordinationFacade internal constructor() {
    private val ids = MessageIdGenerator()
    private val inboxes = InboxRegistry()
    private val router = MessageRouter(inboxes)
    private val requests = RequestBroker(router, ids)
    private val topics = TopicBus()
    private val resources = SharedResourceCoordinator()
    private val closed = MutableStateFlow(false)

    fun nextId(): Long {
        checkOpen()
        return ids.next()
    }

    fun inbox(id: RunId): Inbox {
        checkOpen()
        return inboxes.get(id)
    }

    suspend fun send(message: MessageEnvelope) {
        checkOpen()
        router.send(message)
    }

    suspend fun request(message: MessageEnvelope): MessageEnvelope {
        checkOpen()
        return requests.request(message)
    }

    suspend fun reply(to: MessageEnvelope, payload: String, context: List<ModelItem> = emptyList()) {
        checkOpen()
        requests.reply(to, payload, context)
    }

    suspend fun publish(topic: String, message: MessageEnvelope) {
        checkOpen()
        topics.publish(topic, message.copy(context = message.context.toList()))
    }

    fun subscribe(topic: String): Flow<MessageEnvelope> {
        checkOpen()
        return topics.subscribe(topic)
    }

    suspend fun <T> withResource(id: String, mode: AccessMode = AccessMode.WRITE, block: suspend () -> T): T {
        checkOpen()
        return resources.withResource(id, mode, block)
    }

    val trackedResources: Set<String> get() = resources.trackedResources

    internal fun remove(id: RunId) = inboxes.remove(id)
    internal fun close() {
        if (!closed.compareAndSet(false, true)) return
        requests.close()
        inboxes.close()
    }

    private fun checkOpen() { check(!closed.value) { "Coordination is closed" } }
}
