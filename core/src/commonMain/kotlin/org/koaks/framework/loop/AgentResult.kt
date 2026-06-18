package org.koaks.framework.loop

import org.koaks.framework.model.AgentError
import org.koaks.framework.model.Message
import org.koaks.framework.model.Usage
import org.koaks.framework.policy.TerminationReason

/**
 * The terminal result of a non-streaming [Agent.run].
 */
sealed interface AgentResult {
    val message: Message
    val usage: Usage
    val error: AgentError?

    val text: String get() = message.text
    val isSuccess: Boolean get() = error == null

    data class Completed(
        override val message: Message,
        override val usage: Usage,
    ) : AgentResult {
        override val error: AgentError? get() = null
    }

    data class Terminated(
        override val message: Message,
        override val usage: Usage,
        val reason: TerminationReason,
    ) : AgentResult {
        override val error: AgentError? get() = null

        /** A policy-driven stop is not a natural completion, so it is not a success. */
        override val isSuccess: Boolean get() = false
    }

    data class Failed(
        override val error: AgentError,
        override val usage: Usage = Usage.ZERO,
        override val message: Message = Message.assistant(""),
    ) : AgentResult
}
