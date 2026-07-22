package org.koaks.runtime.acb

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.SendChannel
import org.koaks.framework.loop.AgentEvent

/**
 * A per-instance output sink. Foreground streaming runs forward events into the collector-owned
 * channel; background and result-only runs use [NONE] and allocate no output buffer.
 */
internal interface EventSink {
    /** Forwards one event, applying downstream backpressure when streaming. */
    suspend fun emit(event: AgentEvent)

    /** Completes the stream (normally, or exceptionally with [cause]) so collectors finish. */
    fun close(cause: Throwable? = null)

    companion object {
        /** The zero-cost sink for await-only instances: nothing is buffered or blocked. */
        val NONE: EventSink = NoopEventSink
    }
}

private object NoopEventSink : EventSink {
    override suspend fun emit(event: AgentEvent) {}
    override fun close(cause: Throwable?) {}
}

/** Bridges a runtime-managed instance into the channel owned by `AgentRuntime.stream`. */
internal class ChannelEventSink(
    private val channel: SendChannel<AgentEvent>,
) : EventSink {
    override suspend fun emit(event: AgentEvent) {
        try {
            channel.send(event)
        } catch (c: CancellationException) {
            throw c
        } catch (_: Throwable) {
            // A downstream collector may close the channel with an ordinary exception. From the
            // agent instance's perspective that still means its foreground owner disappeared,
            // so normalize it to cancellation and let runInstance clean up the ACB/descendants.
            throw CancellationException("runtime stream collector is no longer available")
        }
    }

    override fun close(cause: Throwable?) {
        channel.close(cause)
    }
}
