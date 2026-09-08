package org.koaks.runtime.ipc

import org.koaks.runtime.acb.RunId
import org.koaks.framework.model.ModelItem

/** Inter-agent message with directly attached context. A null receiver denotes a broadcast. */
data class RuntimeMessage(
    val id: Long,
    val sender: RunId?,
    val receiver: RunId?,
    val type: String,
    val payload: String = "",
    val context: List<ModelItem> = emptyList(),
    val priority: Int = 0,
    val deadlineMillis: Long? = null,
    /** Set for request/response correlation; `null` for fire-and-forget. */
    val correlationId: Long? = null,
)
