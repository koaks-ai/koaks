package org.koaks.json

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.koaks.framework.model.ModelEvent
import org.koaks.framework.model.ProtocolId
import org.koaks.framework.model.ProviderId

internal fun providerEventFixture(): ModelEvent.ProviderEvent = ModelEvent.ProviderEvent(
    providerId = ProviderId.OpenAIResponses,
    protocolId = ProtocolId.OpenAIResponses,
    type = "response.future.delta",
    source = ModelEvent.ProviderEventSource.HTTP_ERROR,
    eventId = "evt-1",
    sequenceNumber = 42,
    statusCode = 503,
    contentType = "application/json",
    payload = "{\"future_value\":true}",
)

internal val providerEventGolden: JsonObject = buildJsonObject {
    put("type", JsonPrimitive("provider_event"))
    put("provider_id", JsonPrimitive("openai-responses"))
    put("protocol_id", JsonPrimitive("openai-responses"))
    put("event_type", JsonPrimitive("response.future.delta"))
    put("source", JsonPrimitive("http_error"))
    put("event_id", JsonPrimitive("evt-1"))
    put("sequence_number", JsonPrimitive(42))
    put("status_code", JsonPrimitive(503))
    put("content_type", JsonPrimitive("application/json"))
    put("payload", JsonPrimitive("{\"future_value\":true}"))
}
