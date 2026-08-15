package org.koaks.framework.model

import okio.ByteString
import org.koaks.framework.memory.fnv1aHex

data class TranscriptBasis(
    val itemCount: Int,
    val digest: String,
) {
    companion object {
        fun of(items: List<ModelItem>): TranscriptBasis {
            val payload = buildString {
                items.forEach { item ->
                    append(item.ref.value)
                    append(':')
                    append(item::class.simpleName)
                    append('\n')
                }
            }
            return TranscriptBasis(items.size, fnv1aHex(payload))
        }
    }
}

enum class CheckpointScope {
    /** Lives only for the current run (reasoning continuity across tool steps). */
    InRun,

    /** May be persisted across turns; sensitive, opt-in. */
    CrossTurn,
}

/**
 * Opaque provider-native continuation state. Core never interprets [payload];
 * it only compares [basis] against the repaired transcript.
 */
data class ProviderCheckpoint(
    val providerId: ProviderId,
    val codecVersion: Int,
    val basis: TranscriptBasis,
    val scope: CheckpointScope,
    val payload: ByteString,
    val expiresAtEpochMs: Long? = null,
) {
    fun matches(items: List<ModelItem>): Boolean {
        val actual = TranscriptBasis.of(items)
        return basis.itemCount == actual.itemCount && basis.digest == actual.digest
    }
}

fun ProviderCheckpoint?.takeIfValidFor(items: List<ModelItem>, nowEpochMs: Long? = null): ProviderCheckpoint? {
    val checkpoint = this ?: return null
    val expires = checkpoint.expiresAtEpochMs
    if (nowEpochMs != null && expires != null && expires <= nowEpochMs) return null
    return checkpoint.takeIf { it.matches(items) }
}
