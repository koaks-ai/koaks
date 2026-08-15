package org.koaks.framework.loop

import org.koaks.framework.model.AgentError
import org.koaks.framework.model.ModelItem
import org.koaks.framework.model.Usage
import org.koaks.framework.policy.TerminationReason

sealed interface AgentResult {
    val message: ModelItem.Message
    val usage: Usage
    val error: AgentError?

    val text: String get() = message.text
    val isSuccess: Boolean get() = error == null

    data class Completed(
        override val message: ModelItem.Message,
        override val usage: Usage,
    ) : AgentResult {
        override val error: AgentError? get() = null
    }

    data class Terminated(
        override val message: ModelItem.Message,
        override val usage: Usage,
        val reason: TerminationReason,
    ) : AgentResult {
        override val error: AgentError? get() = null
        override val isSuccess: Boolean get() = false
    }

    data class Failed(
        override val error: AgentError,
        override val usage: Usage = Usage.ZERO,
        override val message: ModelItem.Message = ModelItem.assistant(""),
    ) : AgentResult
}
