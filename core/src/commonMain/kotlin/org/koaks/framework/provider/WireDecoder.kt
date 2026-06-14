package org.koaks.framework.provider

import org.koaks.framework.model.ModelEvent

/**
 * A stateful streaming decoder. Unlike a stateless `fromWire(chunk): Event`, this
 * accumulates fragments across chunks: assistant text is a series of deltas, and a
 * tool call's `name` / `arguments` arrive split across multiple chunks. The decoder
 * assembles a complete [org.koaks.framework.model.ToolCall] before emitting
 * [ModelEvent.ToolCallCompleted].
 *
 * Non-streaming is just the degenerate "single chunk" case on the same path.
 */
interface WireDecoder<TResp> {
    /** Consumes one wire chunk, producing 0..N model events. */
    fun accept(chunk: TResp): List<ModelEvent>

    /** Flushes residual state at stream end (completed tool calls, usage). */
    fun finish(): List<ModelEvent>
}
