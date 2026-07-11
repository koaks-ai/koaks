package org.koaks.runtime.ipc

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/**
 * Topic-based publish/subscribe. Subscribers receive messages published to their topic
 * after they start collecting (no replay). Backed by a single [MutableSharedFlow].
 */
class EventBus {
    private val flow = MutableSharedFlow<Pair<String, RuntimeMessage>>(extraBufferCapacity = 256)

    /** Publishes [message] to [topic]; suspends only if buffers are full. */
    suspend fun publish(topic: String, message: RuntimeMessage) {
        flow.emit(topic to message)
    }

    /** A cold stream of messages published to [topic]. */
    fun subscribe(topic: String): Flow<RuntimeMessage> =
        flow.filter { it.first == topic }.map { it.second }
}
