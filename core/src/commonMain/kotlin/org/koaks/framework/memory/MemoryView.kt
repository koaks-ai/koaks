package org.koaks.framework.memory

import org.koaks.framework.model.ModelItem
import org.koaks.framework.model.ProviderCheckpoint

data class MemoryView(
    val transcript: List<ModelItem>,
    val checkpoint: ProviderCheckpoint? = null,
) {
    companion object {
        val EMPTY = MemoryView(emptyList(), null)
    }
}
