package org.koaks.runtime.coordination

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import org.koaks.runtime.run.RunId

/** FIFO inbox for one agent run. */
class Inbox internal constructor(val owner: RunId) {
    private val channel = Channel<MessageEnvelope>(Channel.BUFFERED)
    internal suspend fun send(message: MessageEnvelope) { channel.send(message) }
    fun trySend(message: MessageEnvelope): Boolean = channel.trySend(message).isSuccess
    suspend fun receive(): MessageEnvelope = channel.receive()
    fun asFlow(): Flow<MessageEnvelope> = channel.receiveAsFlow()
    internal fun close() { channel.close() }
}
