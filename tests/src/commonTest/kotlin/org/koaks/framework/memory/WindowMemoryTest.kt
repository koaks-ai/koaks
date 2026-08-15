package org.koaks.framework.memory

import kotlinx.coroutines.test.runTest
import org.koaks.framework.model.ItemRef
import org.koaks.framework.model.ModelItem
import org.koaks.framework.model.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WindowMemoryTest {

    private fun user(t: String) = ModelItem.user(t)
    private fun assistantCall(id: String) = ModelItem.ToolCall(ref = ItemRef(id), name = "tool", arguments = "{}")
    private fun toolResult(id: String) = ModelItem.ToolResult(callRef = ItemRef(id), output = "result")

    @Test
    fun keeps_all_when_under_limit() = runTest {
        val mem = WindowMemory(maxMessages = 10)
        mem.commit(completedTurn(user("hi"), ModelItem.assistant("hello")))
        assertEquals(2, mem.load(emptyList()).transcript.size)
    }

    @Test
    fun drops_oldest_whole_turns_preserving_tool_pairing() = runTest {
        val mem = WindowMemory(maxMessages = 4)
        mem.commit(completedTurn(user("q1"), assistantCall("c1"), toolResult("c1")))
        mem.commit(completedTurn(user("q2"), ModelItem.assistant("a2")))

        val loaded = mem.load(emptyList()).transcript
        assertEquals(listOf("q2"), loaded.userTexts())

        val unresolved = loaded.filterIsInstance<ModelItem.ToolResult>()
        unresolved.forEach { result ->
            assertTrue(loaded.any { it is ModelItem.ToolCall && it.ref == result.callRef })
        }
    }

    @Test
    fun preserves_leading_system_message() = runTest {
        val mem = WindowMemory(maxMessages = 3)
        mem.commit(completedTurn(ModelItem.system("sys")))
        mem.commit(completedTurn(user("q1"), ModelItem.assistant("a1")))
        mem.commit(completedTurn(user("q2"), ModelItem.assistant("a2")))

        val loaded = mem.load(emptyList()).transcript.filterIsInstance<ModelItem.Message>()
        assertEquals(Role.SYSTEM, loaded.first().role, "system message must be preserved at the head")
        assertEquals("sys", loaded.first().text)
    }

    @Test
    fun never_splits_a_single_oversized_turn() = runTest {
        val mem = WindowMemory(maxMessages = 2)
        mem.commit(completedTurn(user("q1"), assistantCall("c1"), toolResult("c1")))
        val loaded = mem.load(emptyList()).transcript
        assertEquals(3, loaded.size)
    }
}

internal fun List<ModelItem>.userTexts(): List<String> =
    filterIsInstance<ModelItem.Message>().filter { it.role == Role.USER }.map { it.text }
