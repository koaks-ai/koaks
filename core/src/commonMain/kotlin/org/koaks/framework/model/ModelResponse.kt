package org.koaks.framework.model

sealed interface IncompleteReason {
    data object MaxOutputTokens : IncompleteReason
    data object ContentFilter : IncompleteReason
    data object Cancelled : IncompleteReason
    data class Other(val code: String) : IncompleteReason
}

/**
 * Terminal truth of one model call. Memory, the tool loop, and the agent result
 * all consume this — they never rebuild state from public streaming events.
 */
sealed interface ModelResponse {
    val id: String?
    val usage: Usage
    val checkpoint: ProviderCheckpoint?
    val output: List<ModelItem>

    data class Completed(
        override val id: String? = null,
        override val output: List<ModelItem> = emptyList(),
        override val usage: Usage = Usage.ZERO,
        override val checkpoint: ProviderCheckpoint? = null,
    ) : ModelResponse

    data class Incomplete(
        override val id: String? = null,
        val reason: IncompleteReason,
        override val output: List<ModelItem> = emptyList(),
        override val usage: Usage = Usage.ZERO,
        override val checkpoint: ProviderCheckpoint? = null,
    ) : ModelResponse

    data class Failed(
        val error: AgentError.ModelError,
        override val id: String? = null,
        override val output: List<ModelItem> = emptyList(),
        override val usage: Usage = Usage.ZERO,
        override val checkpoint: ProviderCheckpoint? = null,
    ) : ModelResponse
}
