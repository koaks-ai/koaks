package org.koaks.memory.vector

import kotlinx.coroutines.test.runTest
import org.koaks.framework.memory.ThreadId
import org.koaks.framework.memory.completedTurn
import org.koaks.framework.model.ModelItem
import kotlin.test.Test
import kotlin.test.assertEquals

class VectorMemoryTest {

    @Test
    fun load_uses_the_current_user_query() = runTest {
        val store = RecordingVectorStore()
        val memory = VectorMemory(store, ThreadId("thread"), topK = 3)

        memory.load(listOf(ModelItem.user("current question")))

        assertEquals("thread", store.lastThread)
        assertEquals("current question", store.lastQuery)
        assertEquals(3, store.lastTopK)
    }

    @Test
    fun commit_indexes_the_successful_turn_messages() = runTest {
        val store = RecordingVectorStore()
        val memory = VectorMemory(store, ThreadId("thread"))
        val items = listOf(ModelItem.user("q"), ModelItem.assistant("a"))

        memory.commit(completedTurn(*items.toTypedArray()))

        assertEquals(items, store.added)
    }

    private class RecordingVectorStore : VectorStore {
        var lastThread: String? = null
        var lastQuery: String? = null
        var lastTopK: Int? = null
        var added: List<ModelItem> = emptyList()

        override suspend fun add(threadId: String, items: List<ModelItem>) {
            lastThread = threadId
            added = items
        }

        override suspend fun search(threadId: String, query: String, topK: Int): List<ModelItem> {
            lastThread = threadId
            lastQuery = query
            lastTopK = topK
            return emptyList()
        }
    }
}
