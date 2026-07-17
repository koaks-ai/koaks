package org.koaks.memory.vector

import org.koaks.framework.memory.Memory
import org.koaks.framework.memory.ThreadId
import org.koaks.framework.model.Message
import org.koaks.framework.model.Role
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
 * Recall is keyed off the most recent USER message in the working set. Because a
 * Thread loads BEFORE appending the new user message, this memory is most useful
 * when given the query directly; here it falls back to recently committed user text.
 */
class VectorMemory(
    private val store: VectorStore,
    private val topK: Int = 8,
) : Memory {

    // Tracks the last query per thread so load() has something to recall against.
    private val lastQuery = HashMap<String, String>()

    override suspend fun commit(thread: ThreadId, messages: List<Message>, usage: Usage) {
        messages.lastOrNull { it.role == Role.USER }?.let { lastQuery[thread.value] = it.text }
        store.add(thread.value, messages)
    }

    override suspend fun load(thread: ThreadId): List<Message> {
        val query = lastQuery[thread.value] ?: return emptyList()
        return store.search(thread.value, query, topK)
    }
}
