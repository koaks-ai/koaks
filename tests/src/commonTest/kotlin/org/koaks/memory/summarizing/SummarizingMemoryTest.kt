package org.koaks.memory.summarizing

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.koaks.framework.loop.FakeLanguageModel
import org.koaks.framework.loop.done
import org.koaks.framework.memory.completedTurn
import org.koaks.framework.memory.stored
import org.koaks.framework.model.ItemRef
import org.koaks.framework.model.ModelEvent
import org.koaks.framework.model.ModelItem
import org.koaks.framework.model.Role
import org.koaks.framework.model.Usage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SummarizingMemoryTest {

    private fun user(t: String) = ModelItem.user(t)
    private fun assistantCall(id: String) = ModelItem.ToolCall(ref = ItemRef(id), name = "tool", arguments = "{}")
    private fun toolResult(id: String) = ModelItem.ToolResult(callRef = ItemRef(id), output = "result")

    private fun summarizer() = FakeLanguageModel(
        listOf(ModelEvent.TextDelta("SUMMARY"), done(Usage.ZERO)),
    )

    @Test
    fun does_not_compact_when_under_token_budget() = runTest {
        val mem = SummarizingMemory(maxTokens = 1000, model = summarizer(), keepRecentTurns = 1)
        mem.commit(completedTurn(user("q1"), ModelItem.assistant("a1"), usage = Usage(promptTokens = 10)))
        mem.commit(completedTurn(user("q2"), ModelItem.assistant("a2"), usage = Usage(promptTokens = 20)))

        val loaded = mem.stored().filterIsInstance<ModelItem.Message>()
        assertEquals(listOf("q1", "q2"), loaded.filter { it.role == Role.USER }.map { it.text })
        assertTrue(loaded.none { it.text.startsWith("Summary of earlier conversation") })
    }

    @Test
    fun compacts_older_turns_when_tokens_exceed_budget() = runTest {
        val mem = SummarizingMemory(maxTokens = 100, model = summarizer(), keepRecentTurns = 1)
        mem.commit(completedTurn(user("q1"), ModelItem.assistant("a1"), usage = Usage(promptTokens = 10)))
        mem.commit(completedTurn(user("q2"), ModelItem.assistant("a2"), usage = Usage(promptTokens = 50)))
        mem.commit(completedTurn(user("q3"), ModelItem.assistant("a3"), usage = Usage(promptTokens = 500)))

        val loaded = mem.stored().filterIsInstance<ModelItem.Message>()
        assertTrue(loaded.first().role == Role.SYSTEM)
        assertTrue(loaded.first().text.startsWith("Summary of earlier conversation"))
        assertEquals(listOf("q3"), loaded.filter { it.role == Role.USER }.map { it.text })
    }

    @Test
    fun preserves_leading_system_and_does_not_split_tool_pairing() = runTest {
        val mem = SummarizingMemory(maxTokens = 100, model = summarizer(), keepRecentTurns = 1)
        mem.commit(completedTurn(ModelItem.system("sys"), usage = Usage(promptTokens = 5)))
        mem.commit(completedTurn(user("q1"), assistantCall("c1"), toolResult("c1"), usage = Usage(promptTokens = 30)))
        mem.commit(completedTurn(user("q2"), ModelItem.assistant("a2"), usage = Usage(promptTokens = 500)))

        val loaded = mem.stored()
        val first = loaded.filterIsInstance<ModelItem.Message>().first()
        assertEquals(Role.SYSTEM, first.role)
        assertEquals("sys", first.text)
        loaded.filterIsInstance<ModelItem.ToolResult>().forEach { result ->
            assertTrue(loaded.any { it is ModelItem.ToolCall && it.ref == result.callRef })
        }
    }

    @Test
    fun stale_summary_never_overwrites_a_concurrent_commit() = runTest {
        val summarizing = CompletableDeferred<Unit>()
        val releaseSummary = CompletableDeferred<Unit>()
        var announced = false
        val model = FakeLanguageModel(
            ArrayDeque(listOf(listOf(ModelEvent.TextDelta("SUMMARY"), done(Usage.ZERO)))),
            beforeEmit = {
                if (!announced) {
                    announced = true
                    summarizing.complete(Unit)
                    releaseSummary.await()
                }
            },
        )
        val mem = SummarizingMemory(maxTokens = 100, model = model, keepRecentTurns = 1)
        mem.commit(completedTurn(user("q1"), ModelItem.assistant("a1"), usage = Usage(promptTokens = 10)))
        mem.commit(completedTurn(user("q2"), ModelItem.assistant("a2"), usage = Usage(promptTokens = 10)))

        val compactingCommit = async {
            mem.commit(completedTurn(user("q3"), ModelItem.assistant("a3"), usage = Usage(promptTokens = 500)))
        }
        runCurrent()
        summarizing.await()

        mem.commit(completedTurn(user("q4"), ModelItem.assistant("a4"), usage = Usage(promptTokens = 10)))
        releaseSummary.complete(Unit)
        compactingCommit.await()

        assertEquals(
            listOf("q1", "q2", "q3", "q4"),
            mem.stored().filterIsInstance<ModelItem.Message>().filter { it.role == Role.USER }.map { it.text },
        )
    }
}
