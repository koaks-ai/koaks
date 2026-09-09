package org.koaks.runtime.coordination

import org.koaks.framework.loop.AgentId
import org.koaks.runtime.run.RunState

data class ChildAgentInfo(
    val agentId: AgentId,
    val name: String,
    val summary: String?,
    val state: RunState,
    val acceptingMessages: Boolean,
)
