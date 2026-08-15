package org.koaks.provider.openai

import kotlinx.serialization.json.JsonObject
import org.koaks.framework.model.CheckpointScope
import org.koaks.framework.model.ProviderCheckpoint
import org.koaks.framework.model.ProviderId
import org.koaks.framework.model.TranscriptBasis
import org.koaks.framework.provider.ProviderCheckpointCodec
import org.koaks.framework.utils.json.JsonUtil
import okio.ByteString.Companion.encodeUtf8

class ResponsesCheckpointCodec : ProviderCheckpointCodec {
    override val providerId: ProviderId = ProviderId.OpenAIResponses
    override val codecVersion: Int = 1

    fun encode(
        responseId: String,
        mode: ResponsesStateMode,
        basis: TranscriptBasis,
        scope: CheckpointScope,
    ): ProviderCheckpoint {
        val payload = JsonUtil.toJson(
            ResponsesCheckpointPayload(responseId, mode.name),
            ResponsesCheckpointPayload.serializer(),
        )
        return ProviderCheckpoint(
            providerId = providerId,
            codecVersion = codecVersion,
            basis = basis,
            scope = scope,
            payload = payload.encodeUtf8(),
        )
    }

    fun decode(checkpoint: ProviderCheckpoint): ResponsesCheckpointPayload? {
        val migrated = migrate(checkpoint)
        return runCatching {
            JsonUtil.fromJson(migrated.payload.utf8(), ResponsesCheckpointPayload.serializer())
        }.getOrNull()
    }

    override fun migrate(checkpoint: ProviderCheckpoint): ProviderCheckpoint {
        require(checkpoint.providerId == providerId) {
            "checkpoint provider '${checkpoint.providerId.value}' does not match codec '${providerId.value}'"
        }
        return checkpoint
    }
}

internal fun JsonObject.string(key: String): String? =
    this[key]?.toString()?.trim('"')?.takeIf { it.isNotEmpty() && it != "null" }
