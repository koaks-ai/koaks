package org.koaks.framework.model

/**
 * Model-layer streaming primitive. The last event of a successful or failed call
 * is always [Finished]; consumers must not reconstruct terminal state from deltas.
 */
sealed interface ModelEvent {

    enum class ReasoningKind {
        SUMMARY,
        RAW,
    }

    enum class ProviderEventSource {
        SSE,
        BODY,
        NDJSON,
        HTTP_ERROR,
    }

    data class Started(val responseId: String?) : ModelEvent

    /** Provider-native continuation state became available before terminal completion. */
    data class CheckpointUpdated(val checkpoint: ProviderCheckpoint) : ModelEvent

    data class TextDelta(val text: String, val itemRef: ItemRef? = null) : ModelEvent

    data class ReasoningDelta(
        val text: String,
        val itemRef: ItemRef? = null,
        val kind: ReasoningKind = ReasoningKind.RAW,
    ) : ModelEvent

    data class RefusalDelta(val text: String, val itemRef: ItemRef? = null) : ModelEvent

    data class AnnotationAdded(val annotation: Annotation, val itemRef: ItemRef? = null) : ModelEvent

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
        val protocolId: ProtocolId = ProtocolId(providerId.value),
        val source: ProviderEventSource = ProviderEventSource.SSE,
        val eventId: String? = null,
        val sequenceNumber: Long? = null,
        val statusCode: Int? = null,
        val contentType: String? = null,
    ) : ModelEvent

    data class Finished(val response: ModelResponse) : ModelEvent
}
