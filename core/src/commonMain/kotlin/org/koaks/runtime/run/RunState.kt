package org.koaks.runtime.run

/**
 * Lifecycle of one run. READY waits for admission; WAITING releases admission when
 * all execution branches are blocked. SUSPENDED is an explicit cooperative pause.
 * COMMITTING protects finalization against cancellation; terminal states never change.
 */
enum class RunState {
    /** The instance has been spawned but scheduling has not started it yet. */
    CREATED,

    /** Waiting for the previous turn of the same ThreadId; no scheduler slot is held. */
    THREAD_QUEUED,

    /** Queued for a concurrency permit. */
    READY,

    /** Actively executing the agent loop. */
    RUNNING,

    /**
     * Blocked on a multi-agent primitive while already RUNNING-admitted:
     * inbox [org.koaks.runtime.coordination.receiveMessage], message request await, or
     * contended resource acquire. Not used for scheduler queueing (that is [READY]).
     */
    WAITING,

    /** Explicitly paused by the operator; resumable. */
    SUSPENDED,

    /** Successful execution is finalizing its conversation turn; cancellation no longer wins. */
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
    fun canTransitionTo(target: RunState): Boolean = when (this) {
        CREATED -> target == THREAD_QUEUED || target == READY || target == CANCELLED || target == FAILED
        THREAD_QUEUED -> target == READY || target == CANCELLED || target == FAILED
        READY -> target == RUNNING || target == CANCELLED || target == FAILED
        RUNNING -> target != CREATED && target != THREAD_QUEUED && target != READY
        WAITING -> target == RUNNING || target == COMMITTING || target == CANCELLED || target == FAILED || target == FINISHED
        SUSPENDED -> target == RUNNING || target == CANCELLED || target == FAILED
        COMMITTING -> target == FINISHED || target == FAILED
        FAILED, FINISHED, CANCELLED -> false
    }
}
