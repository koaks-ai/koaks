package org.koaks.memory.summarizing

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.koaks.framework.loop.FakeLanguageModel
import org.koaks.framework.model.Message
import org.koaks.framework.model.ModelEvent
import org.koaks.framework.model.Role
import org.koaks.framework.model.ToolCall
import org.koaks.framework.model.Usage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
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
        mem.commit(listOf(user("q1"), Message.assistant("a1")), Usage(promptTokens = 10))
        mem.commit(listOf(user("q2"), Message.assistant("a2")), Usage(promptTokens = 20))

        val loaded = mem.load(user(""))
        // Nothing summarized — raw turns preserved.
        assertEquals(listOf("q1", "q2"), loaded.filter { it.role == Role.USER }.map { it.text })
        assertTrue(loaded.none { it.text.startsWith("Summary of earlier conversation") })
    }

    @Test
    fun compacts_older_turns_when_tokens_exceed_budget() = runTest {
        val mem = SummarizingMemory(maxTokens = 100, model = summarizer(), keepRecentTurns = 1)
        mem.commit(listOf(user("q1"), Message.assistant("a1")), Usage(promptTokens = 10))
        mem.commit(listOf(user("q2"), Message.assistant("a2")), Usage(promptTokens = 50))
        // This run reports tokens over the budget → compaction of everything but the last turn.
        mem.commit(listOf(user("q3"), Message.assistant("a3")), Usage(promptTokens = 500))

        val loaded = mem.load(user(""))
        // Older turns collapsed into a single summary system message; only the last turn kept raw.
        assertTrue(loaded.first().role == Role.SYSTEM)
        assertTrue(loaded.first().text.startsWith("Summary of earlier conversation"))
        assertEquals(listOf("q3"), loaded.filter { it.role == Role.USER }.map { it.text })
    }

    @Test
    fun preserves_leading_system_and_does_not_split_tool_pairing() = runTest {
        val mem = SummarizingMemory(maxTokens = 100, model = summarizer(), keepRecentTurns = 1)
        mem.commit(listOf(Message.system("sys")), Usage(promptTokens = 5))
        mem.commit(listOf(user("q1"), assistantCall("c1"), toolResult("c1")), Usage(promptTokens = 30))
        mem.commit(listOf(user("q2"), Message.assistant("a2")), Usage(promptTokens = 500))

        val loaded = mem.load(user(""))
        // Leading system survives at the head.
        assertEquals(Role.SYSTEM, loaded.first().role)
        assertEquals("sys", loaded.first().text)

        // No orphaned tool result: any tool message kept must retain its assistant call.
        loaded.filter { it.role == Role.TOOL }.forEach { tm ->
            val callId = (tm.parts.first() as org.koaks.framework.model.ContentPart.ToolResultPart).callId
            assertTrue(loaded.any { m -> m.toolCalls.any { it.id == callId } })
        }
    }

    @Test
    fun stale_summary_never_overwrites_a_concurrent_commit() = runTest {
        val summarizing = CompletableDeferred<Unit>()
        val releaseSummary = CompletableDeferred<Unit>()
        var announced = false
        val model = FakeLanguageModel(
            ArrayDeque(listOf(listOf(ModelEvent.TextDelta("SUMMARY"), ModelEvent.Completed(Usage.ZERO)))),
            beforeEmit = {
                if (!announced) {
                    announced = true
                    summarizing.complete(Unit)
                    releaseSummary.await()
                }
            },
        )
        val mem = SummarizingMemory(maxTokens = 100, model = model, keepRecentTurns = 1)
        mem.commit(listOf(user("q1"), Message.assistant("a1")), Usage(promptTokens = 10))
        mem.commit(listOf(user("q2"), Message.assistant("a2")), Usage(promptTokens = 10))

        val compactingCommit = async {
            mem.commit(listOf(user("q3"), Message.assistant("a3")), Usage(promptTokens = 500))
        }
        runCurrent()
        summarizing.await()

        mem.commit(listOf(user("q4"), Message.assistant("a4")), Usage(promptTokens = 10))
        releaseSummary.complete(Unit)
        compactingCommit.await()

        assertEquals(
            listOf("q1", "q2", "q3", "q4"),
            mem.load(user("")).filter { it.role == Role.USER }.map { it.text },
        )
    }
}
