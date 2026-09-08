package org.koaks.runtime.internal

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.datetime.Clock
import org.koaks.framework.loop.AgentId
import org.koaks.framework.memory.ThreadId
import org.koaks.runtime.run.RunEventEnvelope
import org.koaks.runtime.run.RunEventPayload
import org.koaks.runtime.run.RunId

/**
 * Non-blocking, bounded per-run event history. A slow or absent subscriber never parks
 * the agent. Subscribers that fall behind receive an explicit [RunEventPayload.HistoryGap].
 */
internal class RunJournal(
    private val runId: RunId,
    private val agentId: AgentId,
    private val threadId: ThreadId?,
    private val correlationId: String?,
    private val capacity: Int,
) {
    init {
        require(capacity > 0) { "run event buffer capacity must be positive" }
    }

    private data class State(
        val nextSequence: Long = 1,
        val events: List<RunEventEnvelope> = emptyList(),
        val closed: Boolean = false,
        val closeCause: Throwable? = null,
    )

    private val state = MutableStateFlow(State())

    fun append(payload: RunEventPayload) {
        while (true) {
            val current = state.value
            if (current.closed) return
            val event = envelope(current.nextSequence, payload)
            val retained = if (current.events.size < capacity) {
                current.events + event
            } else {
                current.events.drop(1) + event
            }
            val next = current.copy(nextSequence = current.nextSequence + 1, events = retained)
            if (state.compareAndSet(current, next)) return
        }
    }

    fun close(cause: Throwable? = null) {
        while (true) {
            val current = state.value
            if (current.closed) return
            if (state.compareAndSet(current, current.copy(closed = true, closeCause = cause))) return
        }
    }

    fun events(afterSequence: Long? = null): Flow<RunEventEnvelope> = flow {
        require(afterSequence == null || afterSequence >= 0L) { "afterSequence must not be negative" }
        var cursor = afterSequence ?: 0L
        var gapReportedThrough = -1L
        while (true) {
            val current = state.value
            val oldest = current.events.firstOrNull()?.sequence ?: current.nextSequence
            if (cursor < oldest - 1 && gapReportedThrough < oldest - 1) {
                emit(envelope(oldest - 1, RunEventPayload.HistoryGap(cursor, oldest)))
                cursor = oldest - 1
                gapReportedThrough = cursor
            }
            current.events.asSequence()
                .filter { it.sequence > cursor }
                .forEach {
                    emit(it)
                    cursor = it.sequence
                }
            if (current.closed) {
                current.closeCause?.let { throw it }
                return@flow
            }
            state.first { it.nextSequence - 1 > cursor || it.closed }
        }
    }

    private fun envelope(sequence: Long, payload: RunEventPayload) = RunEventEnvelope(
        runId = runId,
        agentId = agentId,
        threadId = threadId,
        correlationId = correlationId,
        sequence = sequence,
        timestampEpochMillis = Clock.System.now().toEpochMilliseconds(),
        payload = payload,
    )
}
