package org.koaks.memory.summarizing

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.koaks.framework.loop.FakeLanguageModel
import org.koaks.framework.loop.done
import org.koaks.framework.loop.fail
import org.koaks.framework.memory.appendAll
import org.koaks.framework.memory.stored
import org.koaks.framework.memory.MemoryProviderId
import org.koaks.framework.memory.ThreadId
import org.koaks.framework.model.ItemRef
import org.koaks.framework.model.ModelEvent
import org.koaks.framework.model.ModelItem
import org.koaks.framework.model.ProviderId
import org.koaks.framework.model.ReplayPolicy
import org.koaks.framework.model.Role
import org.koaks.framework.model.Usage
import org.koaks.framework.model.TranscriptBasis
import okio.ByteString.Companion.encodeUtf8
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SummarizingMemoryTest {

    private fun user(t: String) = ModelItem.user(t)
    private fun assistantCall(id: String) = ModelItem.ToolCall(ref = ItemRef(id), name = "tool", arguments = "{}")
    private fun toolResult(id: String) = ModelItem.ToolResult(callRef = ItemRef(id), output = "result")
    private fun overflowAssistant(label: String) = ModelItem.assistant(label + "x".repeat(200))

    private fun summarizer() = FakeLanguageModel(
        listOf(ModelEvent.TextDelta("SUMMARY"), done(Usage.ZERO)),
    )

    @Test
    fun does_not_compact_when_under_char_budget() = runTest {
        val mem = SummarizingMemory(maxTokens = 10_000, model = summarizer(), keepRecentTurns = 1)
        mem.appendAll(user("q1"), ModelItem.assistant("a1"))
        mem.appendAll(user("q2"), ModelItem.assistant("a2"))

        val loaded = mem.stored().filterIsInstance<ModelItem.Message>()
        assertEquals(listOf("q1", "q2"), loaded.filter { it.role == Role.USER }.map { it.text })
        assertTrue(loaded.none { it.text.startsWith("Summary of earlier conversation") })
    }

    @Test
    fun compacts_older_turns_when_chars_exceed_budget() = runTest {
        val mem = SummarizingMemory(maxTokens = 80, model = summarizer(), keepRecentTurns = 1)
        mem.appendAll(user("q1"), ModelItem.assistant("a1"))
        mem.appendAll(user("q2"), ModelItem.assistant("a2"))
        mem.appendAll(user("q3"), overflowAssistant("a3"))

        val loaded = mem.stored().filterIsInstance<ModelItem.Message>()
        assertTrue(loaded.first().role == Role.SYSTEM)
        assertTrue(loaded.first().text.startsWith("Summary of earlier conversation"))
        assertEquals(listOf("q3"), loaded.filter { it.role == Role.USER }.map { it.text })
    }

    @Test
    fun preserves_leading_system_and_does_not_split_tool_pairing() = runTest {
        val mem = SummarizingMemory(maxTokens = 80, model = summarizer(), keepRecentTurns = 1)
        mem.append(ModelItem.system("sys"))
        mem.appendAll(user("q1"), assistantCall("c1"), toolResult("c1"))
        mem.appendAll(user("q2"), overflowAssistant("a2"))

        val loaded = mem.stored()
        val first = loaded.filterIsInstance<ModelItem.Message>().first()
        assertEquals(Role.SYSTEM, first.role)
        assertEquals("sys", first.text)
        loaded.filterIsInstance<ModelItem.ToolResult>().forEach { result ->
            assertTrue(loaded.any { it is ModelItem.ToolCall && it.ref == result.callRef })
        }
    }

    @Test
    fun stale_summary_never_overwrites_a_concurrent_append() = runTest {
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
        val mem = SummarizingMemory(maxTokens = 80, model = model, keepRecentTurns = 1)
        mem.appendAll(user("q1"), ModelItem.assistant("a1"))
        mem.appendAll(user("q2"), ModelItem.assistant("a2"))

        val compactingCommit = async {
            mem.appendAll(user("q3"), overflowAssistant("a3"))
        }
        runCurrent()
        summarizing.await()

        mem.appendAll(user("q4"), ModelItem.assistant("a4"))
        releaseSummary.complete(Unit)
        compactingCommit.await()

        assertTrue("q4" in mem.stored().filterIsInstance<ModelItem.Message>().filter { it.role == Role.USER }.map { it.text })
    }

    @Test
    fun provider_preserves_raw_items_and_reuses_persisted_summary_after_reopen() = runTest {
        val thread = ThreadId("durable-summary")
        val raw = InMemoryAppendMemoryProvider(MemoryProviderId("raw"))
        val states = InMemorySummaryStateStore()
        val provider = SummarizingMemoryProvider(
            id = MemoryProviderId("summary"),
            delegate = raw,
            stateStore = states,
            model = summarizer(),
            maxTokens = 80,
            keepRecentTurns = 1,
        )
        val memory = provider.open(thread)
        memory.appendAll(user("q1"), ModelItem.assistant("a1"))
        memory.appendAll(user("q2"), ModelItem.assistant("a2"))
        memory.appendAll(user("q3"), overflowAssistant("a3"))

        val projected = memory.load(emptyList()).transcript.filterIsInstance<ModelItem.Message>()
        assertTrue(projected.any { it.role == Role.SYSTEM && it.text.contains("SUMMARY") })
        assertEquals(listOf("q3"), projected.filter { it.role == Role.USER }.map { it.text })

        val rawMessages = raw.open(thread).load(emptyList()).transcript.filterIsInstance<ModelItem.Message>()
        assertEquals(listOf("q1", "q2", "q3"), rawMessages.filter { it.role == Role.USER }.map { it.text })

        val reopened = provider.open(thread).load(emptyList()).transcript.filterIsInstance<ModelItem.Message>()
        assertEquals(projected.map { it.ref }, reopened.map { it.ref })
    }

    @Test
    fun invalid_summary_basis_falls_back_to_raw_history() = runTest {
        val thread = ThreadId("invalid-basis")
        val raw = InMemoryAppendMemoryProvider(MemoryProviderId("raw-invalid"))
        val states = InMemorySummaryStateStore()
        val rawMemory = raw.open(thread)
        rawMemory.appendAll(user("q1"), ModelItem.assistant("a1"))
        states.save(
            thread,
            SummaryCheckpoint(
                basis = TranscriptBasis(1, "sha256:not-the-transcript"),
                summary = ModelItem.system("invalid summary"),
                sourceItemRef = "turn",
                createdAtEpochMillis = 1,
            ),
        )
        val provider = SummarizingMemoryProvider(
            id = MemoryProviderId("summary-invalid"),
            delegate = raw,
            stateStore = states,
            model = summarizer(),
            maxTokens = 80,
        )

        val loaded = provider.open(thread).load(emptyList()).transcript.filterIsInstance<ModelItem.Message>()
        assertEquals(listOf("q1"), loaded.filter { it.role == Role.USER }.map { it.text })
        assertTrue(loaded.none { it.text == "invalid summary" })
        assertEquals(null, states.load(thread))
    }

    @Test
    fun compaction_failure_preserves_previous_checkpoint_and_does_not_duplicate_raw_items() = runTest {
        val thread = ThreadId("failed-compaction")
        val raw = InMemoryAppendMemoryProvider(MemoryProviderId("raw-failure"))
        val states = InMemorySummaryStateStore()
        val observed = mutableListOf<CompactionEvent>()
        val model = FakeLanguageModel(
            listOf(ModelEvent.TextDelta("FIRST SUMMARY"), done(Usage.ZERO)),
            listOf(fail("summary failed")),
        )
        val provider = SummarizingMemoryProvider(
            id = MemoryProviderId("summary-failure"),
            delegate = raw,
            stateStore = states,
            model = model,
            maxTokens = 80,
            keepRecentTurns = 1,
            observer = CompactionObserver { observed += it },
        )
        val memory = provider.open(thread)
        memory.appendAll(user("q1"), ModelItem.assistant("a1"))
        memory.appendAll(user("q2"), ModelItem.assistant("a2"))
        memory.appendAll(user("q3"), overflowAssistant("a3"))
        val previous = states.load(thread)
        val q4 = user("q4")
        val a4 = overflowAssistant("a4")

        assertFailsWith<IllegalStateException> { memory.appendAll(q4, a4) }
        runCatching { memory.appendAll(q4, a4) }

        assertEquals(previous, states.load(thread))
        assertTrue(observed.last() is CompactionEvent.Failed)
        val rawUsers = raw.open(thread).load(emptyList()).transcript
            .filterIsInstance<ModelItem.Message>()
            .filter { it.role == Role.USER }
            .map { it.text }
        assertEquals(listOf("q1", "q2", "q3", "q4"), rawUsers)
    }

    @Test
    fun projected_summary_preserves_required_provider_items_from_compacted_turns() = runTest {
        val thread = ThreadId("required-provider-item")
        val raw = InMemoryAppendMemoryProvider(MemoryProviderId("raw-required"))
        val required = ModelItem.ProviderItem(
            providerId = ProviderId.OpenAIResponses,
            kind = "encrypted_reasoning",
            displayText = "reasoning",
            replay = ReplayPolicy.Required,
            payload = "opaque".encodeUtf8(),
        )
        val provider = SummarizingMemoryProvider(
            id = MemoryProviderId("summary-required"),
            delegate = raw,
            stateStore = InMemorySummaryStateStore(),
            model = summarizer(),
            maxTokens = 80,
            keepRecentTurns = 1,
        )
        val memory = provider.open(thread)
        memory.appendAll(user("q1"), required, ModelItem.assistant("a1"))
        memory.appendAll(user("q2"), ModelItem.assistant("a2"))
        memory.appendAll(user("q3"), overflowAssistant("a3"))

        val projected = memory.load(emptyList()).transcript
        assertTrue(projected.any { it is ModelItem.ProviderItem && it.ref == required.ref })
    }
}
