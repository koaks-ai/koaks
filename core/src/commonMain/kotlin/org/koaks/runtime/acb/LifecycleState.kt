package org.koaks.runtime.acb

/**
 * The lifecycle state of a single agent run instance, modeled after an OS process
 * state. Named [LifecycleState] (not `AgentState`) to avoid colliding with core's
 * per-run [org.koaks.framework.loop.AgentState] working set.
 *
 * Legal transitions (cooperative, coroutine-backed):
 *
 * ```
 * CREATED -> THREAD_QUEUED -> READY -> RUNNING -> {WAITING <-> RUNNING} -> FINISHED
 *                        |                                   |
 *                        +--> SUSPENDED <-> RUNNING          |
 *                        +--> COMMITTING -> FINISHED         |
 *                        +--> FAILED                         |
 *                        +--> CANCELLED  <------ (any non-terminal)
 * ```
 *
 * **READY vs WAITING**: READY means admitted but queued for a concurrency slot
 * (scheduler). WAITING means the instance already holds a slot and is blocked on a
 * multi-agent primitive — mailbox receive, request/response reply, or acquiring a
 * shared [org.koaks.runtime.resource.ResourceRegistry] lock.
 */
enum class LifecycleState {
    /** The instance has been spawned but scheduling has not started it yet. */
    CREATED,

    /** Waiting for the previous turn of the same ThreadId; no scheduler slot is held. */
    THREAD_QUEUED,

    /** Admitted by the scheduler, waiting for a concurrency permit. */
    READY,

    /** Actively executing the agent loop. */
    RUNNING,

    /**
     * Blocked on a multi-agent primitive while already RUNNING-admitted:
     * mailbox [org.koaks.runtime.ipc.receiveMessage], IPC request await, or
     * contended resource acquire. Not used for scheduler queueing (that is [READY]).
     */
    WAITING,

    /** Explicitly paused by the operator; resumable. */
    SUSPENDED,

    /** Successful execution is durably committing its Thread turn; cancellation no longer wins. */
    COMMITTING,

    /** Terminated by an error (non-recoverable at the instance level). */
    FAILED,

    /** Reached a terminal outcome (natural completion or policy stop). */
    FINISHED,

    /** Cancelled cooperatively (operator, quota, deadline, or parent propagation). */
    CANCELLED,
    ;

    /** Terminal states never transition again. */
    val isTerminal: Boolean
        get() = this == FAILED || this == FINISHED || this == CANCELLED

    /** Whether a transition from this state to [target] is permitted. */
    fun canTransitionTo(target: LifecycleState): Boolean = when (this) {
        CREATED -> target == THREAD_QUEUED || target == READY || target == CANCELLED
        THREAD_QUEUED -> target == READY || target == CANCELLED
        READY -> target == RUNNING || target == CANCELLED
        RUNNING -> target != CREATED && target != THREAD_QUEUED && target != READY
        WAITING -> target == RUNNING || target == COMMITTING || target == CANCELLED || target == FAILED || target == FINISHED
        SUSPENDED -> target == RUNNING || target == CANCELLED
        COMMITTING -> target == FINISHED || target == FAILED
        FAILED, FINISHED, CANCELLED -> false
    }
}
