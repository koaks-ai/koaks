package org.koaks.framework.provider

import org.koaks.framework.model.ModelEvent
import org.koaks.framework.transport.WireFrame

/**
 * Stateful streaming decoder. Consumes [WireFrame]s (not pre-parsed JSON) so
 * heterogeneous SSE event types can be dispatched by event name.
 */
interface WireDecoder {
    fun accept(frame: WireFrame): List<ModelEvent>
    fun finish(): List<ModelEvent>
}
