package org.koaks.runtime.acb

import org.koaks.framework.loop.AgentEvent

/** Records runtime-managed output without coupling execution to observer speed. */
internal interface EventSink {
    /** Records one event in emission order. */
    suspend fun emit(event: AgentEvent)

    /** Completes the event source normally or exceptionally. */
    fun close(cause: Throwable? = null)
}

/** Records agent content in the same per-run journal as lifecycle events. */
internal class JournalEventSink(
    private val journal: RunEventJournal,
) : EventSink {
    override suspend fun emit(event: AgentEvent) {
        journal.append(RunEventPayload.Agent(event))
    }

    override fun close(cause: Throwable?) {
        journal.close(cause)
    }
}
