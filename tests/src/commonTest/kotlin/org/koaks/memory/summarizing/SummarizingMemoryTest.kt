package org.koaks.memory.summarizing

import kotlinx.coroutines.test.runTest
import org.koaks.framework.loop.FakeLanguageModel
import org.koaks.framework.memory.ThreadId
import org.koaks.framework.model.Message
import org.koaks.framework.model.ModelEvent
import org.koaks.framework.model.Role
import org.koaks.framework.model.ToolCall
import org.koaks.framework.model.Usage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SummarizingMemoryTest {

    private fun user(t: String) = Message.user(t)
    private fun assistantCall(id: String) = Message.assistant("", listOf(ToolCall(id, "tool", "{}")))
    private fun toolResult(id: String) = Message.tool(id, "result")

    private fun summarizer() = FakeLanguageModel(
        listOf(ModelEvent.TextDelta("SUMMARY"), ModelEvent.Completed(Usage.ZERO)),
    )

    @Test
    fun does_not_compact_when_under_token_budget() = runTest {
        val mem = SummarizingMemory(maxTokens = 1000, model = summarizer(), keepRecentTurns = 1)
        val id = ThreadId("t")
        mem.commit(id, listOf(user("q1"), Message.assistant("a1")), Usage(promptTokens = 10))
        mem.commit(id, listOf(user("q2"), Message.assistant("a2")), Usage(promptTokens = 20))

        val loaded = mem.load(id)
        // Nothing summarized — raw turns preserved.
        assertEquals(listOf("q1", "q2"), loaded.filter { it.role == Role.USER }.map { it.text })
        assertTrue(loaded.none { it.text.startsWith("Summary of earlier conversation") })
    }

    @Test
    fun compacts_older_turns_when_tokens_exceed_budget() = runTest {
        val mem = SummarizingMemory(maxTokens = 100, model = summarizer(), keepRecentTurns = 1)
        val id = ThreadId("t")
        mem.commit(id, listOf(user("q1"), Message.assistant("a1")), Usage(promptTokens = 10))
        mem.commit(id, listOf(user("q2"), Message.assistant("a2")), Usage(promptTokens = 50))
        // This run reports tokens over the budget → compaction of everything but the last turn.
        mem.commit(id, listOf(user("q3"), Message.assistant("a3")), Usage(promptTokens = 500))

        val loaded = mem.load(id)
        // Older turns collapsed into a single summary system message; only the last turn kept raw.
        assertTrue(loaded.first().role == Role.SYSTEM)
        assertTrue(loaded.first().text.startsWith("Summary of earlier conversation"))
        assertEquals(listOf("q3"), loaded.filter { it.role == Role.USER }.map { it.text })
    }

    @Test
    fun preserves_leading_system_and_does_not_split_tool_pairing() = runTest {
        val mem = SummarizingMemory(maxTokens = 100, model = summarizer(), keepRecentTurns = 1)
        val id = ThreadId("t")
        mem.commit(id, listOf(Message.system("sys")), Usage(promptTokens = 5))
        mem.commit(id, listOf(user("q1"), assistantCall("c1"), toolResult("c1")), Usage(promptTokens = 30))
        mem.commit(id, listOf(user("q2"), Message.assistant("a2")), Usage(promptTokens = 500))

        val loaded = mem.load(id)
        // Leading system survives at the head.
        assertEquals(Role.SYSTEM, loaded.first().role)
        assertEquals("sys", loaded.first().text)

        // No orphaned tool result: any tool message kept must retain its assistant call.
        loaded.filter { it.role == Role.TOOL }.forEach { tm ->
            val callId = (tm.parts.first() as org.koaks.framework.model.ContentPart.ToolResultPart).callId
            assertTrue(loaded.any { m -> m.toolCalls.any { it.id == callId } })
        }
    }
}
