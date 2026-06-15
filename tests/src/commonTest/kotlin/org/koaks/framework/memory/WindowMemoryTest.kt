package org.koaks.framework.memory

import kotlinx.coroutines.test.runTest
import org.koaks.framework.model.Message
import org.koaks.framework.model.Role
import org.koaks.framework.model.ToolCall
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WindowMemoryTest {

    private fun user(t: String) = Message.user(t)
    private fun assistantCall(id: String) = Message.assistant("", listOf(ToolCall(id, "tool", "{}")))
    private fun toolResult(id: String) = Message.tool(id, "result")

    @Test
    fun keeps_all_when_under_limit() = runTest {
        val mem = WindowMemory(maxMessages = 10)
        val id = ThreadId("t1")
        mem.commit(id, listOf(user("hi"), Message.assistant("hello")))
        assertEquals(2, mem.load(id).size)
    }

    @Test
    fun drops_oldest_whole_turns_preserving_tool_pairing() = runTest {
        val mem = WindowMemory(maxMessages = 4)
        val id = ThreadId("t1")
        // Turn 1: user + assistant(call) + tool result (3 msgs)
        mem.commit(id, listOf(user("q1"), assistantCall("c1"), toolResult("c1")))
        // Turn 2: user + assistant (2 msgs)
        mem.commit(id, listOf(user("q2"), Message.assistant("a2")))

        val loaded = mem.load(id)
        // Budget 4 can't fit both turns (3+2=5); the oldest whole turn is dropped.
        assertEquals(listOf("q2"), loaded.filter { it.role == Role.USER }.map { it.text })

        // Critically: no orphaned tool result without its assistant tool-call.
        val toolMsgs = loaded.filter { it.role == Role.TOOL }
        toolMsgs.forEach { tm ->
            val callId = (tm.parts.first() as org.koaks.framework.model.ContentPart.ToolResultPart).callId
            val hasMatchingCall = loaded.any { m -> m.toolCalls.any { it.id == callId } }
            assertTrue(hasMatchingCall, "tool result $callId must keep its assistant tool-call")
        }
    }

    @Test
    fun preserves_leading_system_message() = runTest {
        val mem = WindowMemory(maxMessages = 3)
        val id = ThreadId("t1")
        mem.commit(id, listOf(Message.system("sys")))
        mem.commit(id, listOf(user("q1"), Message.assistant("a1")))
        mem.commit(id, listOf(user("q2"), Message.assistant("a2")))

        val loaded = mem.load(id)
        assertEquals(Role.SYSTEM, loaded.first().role, "system message must be preserved at the head")
        assertEquals("sys", loaded.first().text)
    }

    @Test
    fun never_splits_a_single_oversized_turn() = runTest {
        val mem = WindowMemory(maxMessages = 2)
        val id = ThreadId("t1")
        // A single turn of 3 messages, larger than the cap.
        mem.commit(id, listOf(user("q1"), assistantCall("c1"), toolResult("c1")))
        val loaded = mem.load(id)
        // Kept intact rather than orphaning the tool result.
        assertEquals(3, loaded.size)
    }
}
