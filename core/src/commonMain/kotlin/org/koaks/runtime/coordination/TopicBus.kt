package org.koaks.runtime.coordination

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/** Topic subscriptions receive only messages published after collection starts; no replay. */
internal class TopicBus {
    private val flow = MutableSharedFlow<Pair<String, MessageEnvelope>>(extraBufferCapacity = 256)
    suspend fun publish(topic: String, message: MessageEnvelope) { flow.emit(topic to message) }
    fun subscribe(topic: String): Flow<MessageEnvelope> = flow.filter { it.first == topic }.map { it.second }
}
