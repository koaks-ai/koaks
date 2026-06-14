package org.koaks.framework.policy

import org.koaks.framework.loop.AgentState
import org.koaks.framework.model.AgentError
import org.koaks.framework.model.Message

/**
 * How the loop should recover from an [AgentError]. These are control-flow
 * decisions consumed by the loop (§3.7/§4.2), not middleware.
 */
sealed interface Recovery {
    /** Abort the agent, surfacing the error. */
    data object Propagate : Recovery

    /** Re-run the current step after [delayMs]. Only safe before any TextDelta was emitted. */
    data class Retry(val delayMs: Long, val maxRetries: Int) : Recovery

    /** Continue with a substitute message instead of retrying. */
    data class Substitute(val message: Message) : Recovery
}

/**
 * Decides the [Recovery] for an error. Invoked by the loop, not as middleware.
 * The default propagates everything.
 */
fun interface ErrorPolicy {
    fun decide(error: AgentError, state: AgentState): Recovery

    companion object {
        val PROPAGATE: ErrorPolicy = ErrorPolicy { _, _ -> Recovery.Propagate }
    }
}
