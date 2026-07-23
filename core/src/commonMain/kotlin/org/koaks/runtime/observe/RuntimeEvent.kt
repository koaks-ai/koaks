package org.koaks.runtime.observe

import org.koaks.framework.model.AgentError
import org.koaks.framework.model.Usage
import org.koaks.framework.policy.TerminationReason
import org.koaks.framework.loop.AgentId
import org.koaks.framework.memory.ThreadId
import org.koaks.runtime.acb.RunId
import org.koaks.runtime.acb.TurnId

/**
 * Runtime-level observability events — the "system monitor" data plane. Distinct from
 * core's per-run [org.koaks.framework.loop.AgentEvent]: these describe kernel decisions
 * (spawn, schedule, retry, circuit-breaker, lifecycle transitions) across all instances.
 * Consume via [org.koaks.runtime.AgentRuntime.events]; a GUI console is a separate,
 * optional front-end outside this module.
 */
sealed interface RuntimeEvent {
    data class Spawned(
        val runId: RunId,
        val agentId: AgentId,
        val agentName: String,
        val threadId: ThreadId?,
        val turnId: TurnId?,
        val priority: Int,
        val parent: RunId?,
    ) : RuntimeEvent
    data class Running(
        val runId: RunId,
        val agentId: AgentId,
        val threadId: ThreadId?,
        val turnId: TurnId?,
        val agentName: String,
    ) : RuntimeEvent
    /** Instance blocked on mailbox / IPC reply / shared-resource acquire. */
    data class Waiting(val runId: RunId, val agentId: AgentId, val threadId: ThreadId?, val turnId: TurnId?) : RuntimeEvent
    data class Suspended(val runId: RunId, val agentId: AgentId, val threadId: ThreadId?, val turnId: TurnId?) : RuntimeEvent
    data class Resumed(val runId: RunId, val agentId: AgentId, val threadId: ThreadId?, val turnId: TurnId?) : RuntimeEvent
    data class Finished(val runId: RunId, val agentId: AgentId, val threadId: ThreadId?, val turnId: TurnId?, val usage: Usage) : RuntimeEvent
    data class Terminated(val runId: RunId, val agentId: AgentId, val threadId: ThreadId?, val turnId: TurnId?, val reason: TerminationReason) : RuntimeEvent
    data class Failed(val runId: RunId, val agentId: AgentId, val threadId: ThreadId?, val turnId: TurnId?, val error: AgentError) : RuntimeEvent
    data class Cancelled(val runId: RunId, val agentId: AgentId, val threadId: ThreadId?, val turnId: TurnId?) : RuntimeEvent
    /** A CAPTURE child failed without any caller consuming the failure via AgentHandle.await(). */
    data class UnhandledChildFailure(
        val parentRunId: RunId,
        val childRunId: RunId,
        val childAgentId: AgentId,
        val error: AgentError,
    ) : RuntimeEvent
    data class SideEffectRollback(
        val runId: RunId,
        val agentId: AgentId,
        val threadId: ThreadId,
        val turnId: TurnId,
    ) : RuntimeEvent
    data class Retrying(
        val runId: RunId?,
        val agentId: AgentId,
        val agentName: String,
        val threadId: ThreadId?,
        val turnId: TurnId?,
        val attempt: Int,
        val delayMillis: Long,
    ) : RuntimeEvent
    data class CircuitOpen(
        val runId: RunId?,
        val agentId: AgentId,
        val agentName: String,
        val threadId: ThreadId?,
        val turnId: TurnId?,
    ) : RuntimeEvent
}
