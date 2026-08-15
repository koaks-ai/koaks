package org.koaks.framework.model

/**
 * Model-layer streaming primitive. The last event of a successful or failed call
 * is always [Finished]; consumers must not reconstruct terminal state from deltas.
 */
sealed interface ModelEvent {

    data class Started(val responseId: String?) : ModelEvent

    data class TextDelta(val text: String, val itemRef: ItemRef? = null) : ModelEvent

    data class ReasoningDelta(val text: String, val itemRef: ItemRef? = null) : ModelEvent

    data class ItemAdded(val item: ModelItem) : ModelEvent

    data class ToolCallDelta(
        val id: String,
        val index: Int? = null,
        val nameDelta: String? = null,
        val argumentsDelta: String? = null,
        val itemRef: ItemRef? = null,
    ) : ModelEvent

    data class ToolCallCompleted(val call: ToolCall) : ModelEvent

    data class ProviderEvent(
        val providerId: ProviderId,
        val type: String,
        val payload: String,
    ) : ModelEvent

    data class Finished(val response: ModelResponse) : ModelEvent
}
