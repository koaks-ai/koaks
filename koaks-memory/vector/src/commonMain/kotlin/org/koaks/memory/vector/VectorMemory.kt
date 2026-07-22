package org.koaks.memory.vector

import org.koaks.framework.memory.MemoryProvider
import org.koaks.framework.memory.MemoryProviderId
import org.koaks.framework.memory.ThreadId
import org.koaks.framework.memory.ThreadMemory
import org.koaks.framework.model.Message
import org.koaks.framework.model.Usage

/**
 * Memory backed by semantic recall over a [VectorStore]. Lives in its own module
 * (`koaks-memory:vector`) since it depends on an embedding store — core stays
 * dependency-free.
 *
 * [commit] indexes the run's messages; [load] recalls the [topK] most relevant past
 * messages for the latest user query (no fixed window). As with all memories,
 * filtering/recall happens on the load side; commit faithfully appends.
 *
 * Recall is keyed directly by the current user [query], which Runtime supplies before
 * constructing the model request.
 */
class VectorMemory(
    private val store: VectorStore,
    private val thread: ThreadId,
    private val topK: Int = 8,
) : ThreadMemory {

    override suspend fun commit(messages: List<Message>, usage: Usage) {
        store.add(thread.value, messages)
    }

    override suspend fun load(query: Message): List<Message> =
        store.search(thread.value, query.text, topK)
}

/** Opens one vector-backed memory partition per Runtime Thread. */
class VectorMemoryProvider(
    override val id: MemoryProviderId,
    private val store: VectorStore,
    private val topK: Int = 8,
) : MemoryProvider {
    override suspend fun open(thread: ThreadId): ThreadMemory = VectorMemory(store, thread, topK)
}

fun vectorMemoryProvider(id: String, store: VectorStore, topK: Int = 8): MemoryProvider =
    VectorMemoryProvider(MemoryProviderId(id), store, topK)
