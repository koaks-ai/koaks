package org.koaks.runtime.coordination

/** Delivers point-to-point messages; inbox ownership and request tracking live elsewhere. */
internal class MessageRouter(private val inboxes: InboxRegistry) {
    suspend fun send(message: MessageEnvelope) {
        val receiver = requireNotNull(message.receiver) { "send requires a receiver" }
        inboxes.get(receiver).send(message.copy(context = message.context.toList()))
    }
}
