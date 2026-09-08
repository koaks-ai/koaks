package org.koaks.runtime.coordination

import org.koaks.framework.model.ModelItem
import org.koaks.runtime.run.RunId

/** A message exchanged between agent runs or published to a topic. */
data class MessageEnvelope(
    val id: Long,
    val sender: RunId?,
    val receiver: RunId?,
    val type: String,
    val payload: String = "",
    val context: List<ModelItem> = emptyList(),
    val priority: Int = 0,
    val deadlineMillis: Long? = null,
    val correlationId: Long? = null,
)
