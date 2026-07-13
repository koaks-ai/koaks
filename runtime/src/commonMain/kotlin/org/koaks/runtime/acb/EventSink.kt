package org.koaks.runtime.acb

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koaks.framework.loop.AgentEvent

/**
 * A per-instance output channel — the analogue of a process's stdout. The runtime forwards
 * every outward [AgentEvent] into the sink; a caller that spawned with `observe = true` drains
 * it via [AgentHandle.events]. Created before the run coroutine starts and retained until its
 * sole consumer drains it, so no head events are lost regardless of collection timing.
 */
internal interface EventSink {
    /** The single-consumer output stream. Empty for a non-observing instance. */
    val events: Flow<AgentEvent>

    /** Forwards one event. A no-op when not observing. */
    suspend fun emit(event: AgentEvent)

    /** Emits a final event and completes the stream normally. Safe to call after cancellation. */
    fun finish(event: AgentEvent)

    /** Completes the stream (normally, or exceptionally with [cause]) so collectors finish. */
    fun close(cause: Throwable? = null)

    companion object {
        /** The zero-cost sink for await-only instances: nothing is buffered or blocked. */
        val NONE: EventSink = NoopEventSink
    }
}

private object NoopEventSink : EventSink {
    override val events: Flow<AgentEvent> = emptyFlow()
    override suspend fun emit(event: AgentEvent) {}
    override fun finish(event: AgentEvent) {}
    override fun close(cause: Throwable?) {}
}

/**
 * A lossless sink backed by an unlimited [Channel]. Observation is explicit at spawn time,
 * and observed events are retained until the consumer drains them. This keeps [AgentHandle.await]
 * independent from collection and allows late collection without blocking the agent run.
 *
 * The flow is intentionally single-consumer. A second collection fails rather than silently
 * splitting events between competing collectors (the default [receiveAsFlow] behavior). If
 * that consumer stops before the producer completes, the remaining buffer is discarded and
 * subsequent events are ignored so an abandoned observation cannot grow without bound.
 */
internal class ChannelEventSink : EventSink {
    private val channel = Channel<AgentEvent>(Channel.UNLIMITED)
    private val claimLock = Mutex()
    private var claimed = false

    override val events: Flow<AgentEvent> = flow {
        claimLock.withLock {
            check(!claimed) { "AgentHandle.events can only be collected once" }
            claimed = true
        }
        try {
            emitAll(channel.receiveAsFlow())
        } finally {
            // receiveAsFlow does not consume or close its channel when downstream stops early
            // (for example via first()/take() or collector cancellation). Cancel explicitly to
            // discard the retained tail and make future emits no-ops instead of leaking memory.
            channel.cancel()
        }
    }

    override suspend fun emit(event: AgentEvent) {
        // An early-ending consumer cancels the channel. Observation is auxiliary, so losing the
        // consumer must not fail or cancel the agent instance itself.
        channel.trySend(event)
    }

    override fun finish(event: AgentEvent) {
        // Completion callbacks cannot suspend. With an unlimited channel this succeeds whenever
        // the sole consumer has not already abandoned (and cancelled) the observation.
        channel.trySend(event)
        channel.close()
    }

    override fun close(cause: Throwable?) {
        channel.close(cause)
    }
}
