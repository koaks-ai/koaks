package org.koaks.runtime.run

import org.koaks.framework.loop.AgentEvent
import org.koaks.framework.loop.AgentId
import org.koaks.framework.memory.ThreadId
import org.koaks.runtime.observability.RuntimeEvent

/** A totally ordered event emitted by one runtime-managed agent instance. */
data class RunEventEnvelope(
    val runId: RunId,
    val agentId: AgentId,
    val threadId: ThreadId?,
    val correlationId: String?,
    val sequence: Long,
    val timestampEpochMillis: Long,
    val payload: RunEventPayload,
)

sealed interface RunEventPayload {
    data class Message(val status: String, val messageId: Long, val reason: String? = null) : RunEventPayload
    data class Agent(val event: AgentEvent) : RunEventPayload
    data class Lifecycle(val event: RuntimeEvent) : RunEventPayload
    data class HistoryGap(val requestedAfter: Long, val oldestAvailable: Long) : RunEventPayload
}

class RunEventHistoryGapException(
    val requestedAfter: Long,
    val oldestAvailable: Long,
) : IllegalStateException(
    "run event history after $requestedAfter is no longer available; oldest retained sequence is $oldestAvailable",
)
