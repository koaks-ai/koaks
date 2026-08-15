package org.koaks.memory.vector

import org.koaks.framework.memory.ConversationTurn
import org.koaks.framework.memory.MemoryProvider
import org.koaks.framework.memory.MemoryProviderId
import org.koaks.framework.memory.MemoryView
import org.koaks.framework.memory.ThreadId
import org.koaks.framework.memory.ThreadMemory
import org.koaks.framework.memory.TurnRetention
import org.koaks.framework.memory.shouldRetain
import org.koaks.framework.memory.turnHasSideEffects
import org.koaks.framework.model.ModelItem
import org.koaks.framework.model.displayText

/**
 * Memory backed by semantic recall over a [VectorStore]. Lives in its own module
 * (`koaks-memory:vector`) since it depends on an embedding store — core stays
 * dependency-free.
 *
 * [commit] indexes the turn's items; [load] recalls the [topK] most relevant past
 * items for the latest query text. Recall keyed by the current user query, which
 * Runtime supplies before constructing the model request.
 *
 * Returned subsets are unordered relative to the original transcript, so any
 * attached [org.koaks.framework.model.ProviderCheckpoint] is dropped on load.
 */
class VectorMemory(
    private val store: VectorStore,
    private val thread: ThreadId,
    private val topK: Int = 8,
    override val retention: TurnRetention = TurnRetention.Interrupted,
) : ThreadMemory {

    override suspend fun commit(turn: ConversationTurn) {
        if (!shouldRetain(turn, retention, turnHasSideEffects(turn))) return
        store.add(thread.value, turn.items)
    }

    override suspend fun load(query: List<ModelItem>): MemoryView {
        val text = query.lastOrNull()?.displayText().orEmpty()
        return MemoryView(transcript = store.search(thread.value, text, topK), checkpoint = null)
    }
}

class VectorMemoryProvider(
    override val id: MemoryProviderId,
    private val store: VectorStore,
    private val topK: Int = 8,
    private val retention: TurnRetention = TurnRetention.Interrupted,
) : MemoryProvider {
    override suspend fun open(thread: ThreadId): ThreadMemory =
        VectorMemory(store, thread, topK, retention)
}

fun vectorMemoryProvider(id: String, store: VectorStore, topK: Int = 8): MemoryProvider =
    VectorMemoryProvider(MemoryProviderId(id), store, topK)
