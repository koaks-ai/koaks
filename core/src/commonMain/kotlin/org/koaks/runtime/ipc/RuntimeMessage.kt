package org.koaks.runtime.ipc

import org.koaks.runtime.acb.RunId
import org.koaks.runtime.context.ContextRef

/**
 * The unified inter-agent message. Note [contextRefs]: large context is **not** copied
 * into [payload] — only references travel, and the receiver resolves them by permission.
 * A `null` [receiver] is for topic broadcasts via the [EventBus].
 */
data class RuntimeMessage(
    val id: Long,
    val sender: RunId?,
    val receiver: RunId?,
    val type: String,
    val payload: String = "",
    val contextRefs: List<ContextRef> = emptyList(),
    val priority: Int = 0,
    val deadlineMillis: Long? = null,
    /** Set for request/response correlation; `null` for fire-and-forget. */
    val correlationId: Long? = null,
)
