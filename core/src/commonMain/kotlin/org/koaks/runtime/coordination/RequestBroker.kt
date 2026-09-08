package org.koaks.runtime.coordination

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.koaks.framework.model.ModelItem

/** Matches replies with requests; caller timeout/cancellation always removes the pending entry. */
internal class RequestBroker(private val router: MessageRouter, private val ids: MessageIdGenerator) {
    private data class State(
        val pending: Map<Long, CompletableDeferred<MessageEnvelope>> = emptyMap(),
        val closed: Boolean = false,
    )
    private val state = MutableStateFlow(State())
    internal val pendingCount: Int get() = state.value.pending.size

    suspend fun request(message: MessageEnvelope): MessageEnvelope {
        val correlation = ids.next()
        val response = CompletableDeferred<MessageEnvelope>()
        while (true) {
            val current = state.value
            check(!current.closed) { "Coordination is closed" }
            if (state.compareAndSet(current, current.copy(pending = current.pending + (correlation to response)))) break
        }
        try {
            router.send(message.copy(correlationId = correlation))
            return response.await()
        } finally {
            state.update { it.copy(pending = it.pending - correlation) }
        }
    }

    suspend fun reply(to: MessageEnvelope, payload: String, context: List<ModelItem>) {
        check(!state.value.closed) { "Coordination is closed" }
        val correlation = requireNotNull(to.correlationId) { "message is not a request" }
        val response = MessageEnvelope(
            id = ids.next(), sender = to.receiver, receiver = to.sender,
            type = "${to.type}.reply", payload = payload, context = context.toList(), correlationId = correlation,
        )
        val pending = state.value.pending[correlation]
        if (pending != null && pending.complete(response)) {
            state.update { it.copy(pending = it.pending - correlation) }
        } else if (to.sender != null) {
            // Preserve delivery of late replies to the original sender's inbox.
            router.send(response)
        }
    }

    fun close() {
        while (true) {
            val current = state.value
            if (current.closed) return
            if (state.compareAndSet(current, State(closed = true))) {
                current.pending.values.forEach { it.cancel(CancellationException("Coordination is closed")) }
                return
            }
        }
    }
}
