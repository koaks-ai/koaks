package org.koaks.memory.vector

import org.koaks.framework.model.ModelItem

/**
 * A pluggable vector store for semantic recall. Implementations wrap an embedding
 * model + ANN index (e.g. an in-memory cosine index, or an external service).
 */
interface VectorStore {
    /** Persists [items] under [threadId], computing and indexing their embeddings. */
    suspend fun add(threadId: String, items: List<ModelItem>)

    /** Returns the [topK] items most semantically similar to [query] within [threadId]. */
    suspend fun search(threadId: String, query: String, topK: Int): List<ModelItem>
}
