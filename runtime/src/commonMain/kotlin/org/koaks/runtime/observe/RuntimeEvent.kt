package org.koaks.runtime.observe

import org.koaks.framework.model.AgentError
import org.koaks.framework.model.Usage
import org.koaks.framework.policy.TerminationReason
import org.koaks.runtime.acb.AgentId

/**
 * Runtime-level observability events — the "system monitor" data plane. Distinct from
 * core's per-run [org.koaks.framework.loop.AgentEvent]: these describe kernel decisions
 * (spawn, schedule, retry, circuit-breaker, lifecycle transitions) across all instances.
 * Consume via [org.koaks.runtime.AgentRuntime.events]; a GUI console is a separate,
 * optional front-end outside this module.
 */
sealed interface RuntimeEvent {
    data class Spawned(val id: AgentId, val agentName: String, val priority: Int, val parent: AgentId?) : RuntimeEvent
    data class Running(val id: AgentId, val agentName: String) : RuntimeEvent
    /** Instance blocked on mailbox / IPC reply / shared-resource acquire. */
    data class Waiting(val id: AgentId) : RuntimeEvent
    data class Suspended(val id: AgentId) : RuntimeEvent
    data class Resumed(val id: AgentId) : RuntimeEvent
    data class Finished(val id: AgentId, val usage: Usage) : RuntimeEvent
    data class Terminated(val id: AgentId, val reason: TerminationReason) : RuntimeEvent
    data class Failed(val id: AgentId, val error: AgentError) : RuntimeEvent
    data class Cancelled(val id: AgentId) : RuntimeEvent
    data class Retrying(val agentName: String, val attempt: Int, val delayMillis: Long) : RuntimeEvent
    data class CircuitOpen(val agentName: String) : RuntimeEvent
}
