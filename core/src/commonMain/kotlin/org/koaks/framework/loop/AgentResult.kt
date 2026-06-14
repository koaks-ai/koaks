package org.koaks.framework.loop

import org.koaks.framework.model.AgentError
import org.koaks.framework.model.Message
import org.koaks.framework.model.Usage

/**
 * The terminal result of a non-streaming [Agent.run].
 *
 * @property message the final assistant message.
 * @property usage accumulated token usage.
 * @property error set if the run ended in failure rather than [AgentEvent.Finished].
 */
data class AgentResult(
    val message: Message,
    val usage: Usage,
    val error: AgentError? = null,
) {
    val text: String get() = message.text
    val isSuccess: Boolean get() = error == null
}
