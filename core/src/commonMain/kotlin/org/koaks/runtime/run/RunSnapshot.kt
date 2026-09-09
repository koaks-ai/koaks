package org.koaks.runtime.run

import org.koaks.framework.loop.AgentId
import org.koaks.framework.memory.ThreadId
import org.koaks.framework.model.AgentError
import org.koaks.framework.model.Usage

/** Immutable identity, lifecycle, usage and child relationships for one run. */
data class RunSnapshot(
    val runId: RunId,
    val agentId: AgentId,
    val agentName: String,
    val threadId: ThreadId?,
    val state: RunState,
    val priority: Int,
    val parent: RunId?,
    val correlationId: String?,
    val children: List<RunId>,
    val acceptingChildren: Boolean,
    val usage: Usage,
    val stepsCompleted: Int,
    val toolCalls: Int,
    val elapsedMillis: Long,
    val error: AgentError?,
    val summary: String? = null,
    val acceptingMessages: Boolean = true,
)
