package org.koaks.framework.memory

import org.koaks.framework.model.ItemRef
import org.koaks.framework.model.ModelItem
import org.koaks.framework.model.ProviderCheckpoint
import org.koaks.framework.model.Usage

sealed interface InterruptReason {
    data object Cancelled : InterruptReason
    data object Failed : InterruptReason
    data class Policy(val detail: String) : InterruptReason
}

data class PendingWork(
    val unresolvedCalls: List<ItemRef> = emptyList(),
    val partialText: String? = null,
    val partialItem: ItemRef? = null,
)

sealed interface TurnStatus {
    data object Completed : TurnStatus
    data class Interrupted(val reason: InterruptReason, val pending: PendingWork) : TurnStatus
}

/**
 * Atomic record of one conversation turn. Written once; [status] captures whether
 * the turn finished naturally or was interrupted. Storage keeps this shape
 * faithfully; [repairTranscript] projects it into a provider-legal online view.
 */
data class ConversationTurn(
    val id: String,
    val status: TurnStatus,
    val items: List<ModelItem>,
    val checkpoint: ProviderCheckpoint? = null,
    val usage: Usage = Usage.ZERO,
)

enum class TurnRetention {
    CompletedOnly,
    Interrupted,
    InterruptedIfSideEffects,
}
