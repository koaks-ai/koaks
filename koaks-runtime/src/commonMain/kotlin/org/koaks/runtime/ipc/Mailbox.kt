package org.koaks.runtime.ipc

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import org.koaks.runtime.acb.AgentId

/**
 * A per-instance inbox backed by a coroutine [Channel] — the point-to-point IPC endpoint.
 * Delivery is FIFO; [asFlow] supports streaming consumption.
 */
class Mailbox internal constructor(val owner: AgentId) {
    private val channel = Channel<RuntimeMessage>(Channel.BUFFERED)

    internal suspend fun send(message: RuntimeMessage) = channel.send(message)

    /** Non-suspending best-effort enqueue; returns whether it was accepted. */
    fun trySend(message: RuntimeMessage): Boolean = channel.trySend(message).isSuccess

    /** Suspends until a message is available. */
    suspend fun receive(): RuntimeMessage = channel.receive()

    /** Streams inbound messages until the mailbox is closed. */
    fun asFlow(): Flow<RuntimeMessage> = channel.receiveAsFlow()

    internal fun close() {
        channel.close()
    }
}
