package org.koaks.memory.vector

import org.koaks.framework.model.Message

/**
 * A pluggable vector store for semantic recall. Implementations wrap an embedding
 * model + ANN index (e.g. an in-memory cosine index, or an external service).
 */
interface VectorStore {
    /** Persists [messages] under [threadId], computing and indexing their embeddings. */
    suspend fun add(threadId: String, messages: List<Message>)

    /** Returns the [topK] messages most semantically similar to [query] within [threadId]. */
    suspend fun search(threadId: String, query: String, topK: Int): List<Message>
}
