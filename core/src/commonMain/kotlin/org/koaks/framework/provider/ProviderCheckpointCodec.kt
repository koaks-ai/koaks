package org.koaks.framework.provider

import org.koaks.framework.model.ProviderCheckpoint
import org.koaks.framework.model.ProviderId

/**
 * Versioned codec for a provider's opaque checkpoint payload. Core never reads
 * the payload; the owning provider migrates and round-trips it.
 */
interface ProviderCheckpointCodec {
    val providerId: ProviderId
    val codecVersion: Int

    fun migrate(checkpoint: ProviderCheckpoint): ProviderCheckpoint {
        require(checkpoint.providerId == providerId) {
            "checkpoint provider '${checkpoint.providerId.value}' does not match codec '${providerId.value}'"
        }
        return checkpoint
    }
}
