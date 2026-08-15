package org.koaks.java

import org.koaks.framework.loop.AgentResult

/** Raised when a typed structured run ends without a natural completed result. */
class AgentRunException(
    val result: AgentResult,
) : RuntimeException(messageFor(result)) {
    companion object {
        private fun messageFor(result: AgentResult): String = when (result) {
            is AgentResult.Failed -> result.error.message
            is AgentResult.Incomplete -> "agent run incomplete: ${result.reason}"
            is AgentResult.Terminated -> "agent run terminated: ${result.reason}"
            is AgentResult.Completed -> "agent run completed"
        }
    }
}

/** Raised when Jackson cannot decode the model's structured response. */
class StructuredOutputException(
    val rawResponse: String,
    cause: Throwable,
) : RuntimeException("failed to decode structured Agent output", cause)
