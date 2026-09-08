package org.koaks.runtime.conversation

import org.koaks.framework.loop.AgentId
import org.koaks.framework.memory.MemoryProviderId
import org.koaks.framework.memory.ThreadId
import org.koaks.runtime.run.RunId

/** Current memory binding and admission queue for a conversation. */
data class ConversationSnapshot(
    val id: ThreadId,
    val memoryProviderId: MemoryProviderId,
    val participants: Set<AgentId>,
    val activeRun: RunId?,
    val queuedRuns: List<RunId>,
)
