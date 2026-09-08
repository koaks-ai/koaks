package org.koaks.framework.memory

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.koaks.framework.loop.AgentResult
import org.koaks.framework.loop.FakeLanguageModel
import org.koaks.framework.loop.NoArgs
import org.koaks.framework.loop.OutputSpec
import org.koaks.framework.loop.agent
import org.koaks.framework.loop.done
import org.koaks.framework.loop.tool
import org.koaks.framework.model.*
import org.koaks.runtime.AgentRuntime
import org.koaks.runtime.coordination.spawnChild
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class MessagePersistenceTest {
    private class RecordingMemory : ThreadMemory {
        val items = mutableListOf<ModelItem>()
        val writes = mutableListOf<ModelItem>()
        val resultWritten = CompletableDeferred<Unit>()
        private var writing = false
        override suspend fun load(query: List<ModelItem>) = MemoryView(items.toList())
        override suspend fun append(item: ModelItem) {
            check(!writing) { "Concurrent writes reached storage" }
            writing = true
            try {
                delay(1)
                items.upsert(item)
                writes += item
                if (item is ModelItem.ToolResult) resultWritten.complete(Unit)
            } finally {
                writing = false
            }
        }
    }

    @Test
    fun completed_items_precede_finished_and_cancellation_flushes_to_suspending_memory() = runTest {
        val memory = RecordingMemory()
        val paused = CompletableDeferred<Unit>()
        val first = ModelItem.assistant("first", ref = ItemRef("first"))
        val call = ToolCall("call", "work", "{}")
        val model = object : LanguageModel {
            override val capabilities = ModelCapabilities()
            override fun stream(request: ModelRequest): Flow<ModelEvent> = flow {
                emit(ModelEvent.ItemAdded(first))
                emit(ModelEvent.ToolCallCompleted(call))
                emit(ModelEvent.TextDelta("half", ItemRef("partial")))
                paused.complete(Unit)
                awaitCancellation()
            }
        }
        val a = agent {
            id = "cancel-prefix"
            model { custom(model) }
            memory { custom("recording") { memory } }
        }
        AgentRuntime { dispatcher = StandardTestDispatcher(testScheduler) }.use { runtime ->
            val handle = runtime.spawn(a, "question", thread = "thread")
            paused.await()
            assertEquals(listOf("question", "first", "tool call work({})"), memory.items.map { it.displayText() })
            handle.cancel("stop")
            handle.join()
            assertEquals("half", memory.items.last().displayText())
            assertEquals(1, memory.writes.count { it.ref == first.ref })
            assertEquals(1, memory.writes.count { it.ref == call.ref })
            assertEquals(1, MemoryView(memory.items).forOnlineUse().transcript.filterIsInstance<ModelItem.ToolResult>().size)
        }
    }

    @Test
    fun stream_ending_without_finished_keeps_partial_and_reports_failure() = runTest {
        val memory = RecordingMemory()
        val model = object : LanguageModel {
            override val capabilities = ModelCapabilities()
            override fun stream(request: ModelRequest): Flow<ModelEvent> = flow {
                emit(ModelEvent.TextDelta("partial", ItemRef("partial")))
            }
        }
        val a = agent {
            id = "broken-stream"
            model { custom(model) }
            memory { custom("recording") { memory } }
        }
        AgentRuntime { dispatcher = StandardTestDispatcher(testScheduler) }.use { runtime ->
            assertTrue(runtime.run(a, "question", thread = "thread") is AgentResult.Failed)
            assertEquals(listOf("question", "partial"), memory.items.map { it.displayText() })
        }
    }

    @Test
    fun completed_parallel_result_survives_cancellation_of_other_tool() = runTest {
        val memory = RecordingMemory()
        val model = FakeLanguageModel(listOf(
            ModelEvent.ToolCallCompleted(ToolCall("fast", "fast", "{}")),
            ModelEvent.ToolCallCompleted(ToolCall("slow", "slow", "{}")),
            done(Usage.ZERO),
        ))
        val a = agent {
            id = "parallel-results"
            model { custom(model) }
            memory { custom("recording") { memory } }
            tools {
                tool<NoArgs>("fast", "fast") { "saved result" }
                tool<NoArgs>("slow", "slow") { awaitCancellation() }
            }
        }
        AgentRuntime { dispatcher = StandardTestDispatcher(testScheduler) }.use { runtime ->
            val handle = runtime.spawn(a, "question", thread = "thread")
            memory.resultWritten.await()
            assertEquals("saved result", memory.items.filterIsInstance<ModelItem.ToolResult>().single().output)
            handle.cancel("stop slow tool")
            handle.join()
            assertEquals(1, memory.items.filterIsInstance<ModelItem.ToolResult>().size)
        }
    }

    @Test
    fun context_is_snapshotted_at_spawn_and_not_persisted() = runTest {
        val memory = RecordingMemory()
        val model = FakeLanguageModel(
            listOf(ModelEvent.TextDelta("answer one"), done(Usage.ZERO)),
            listOf(ModelEvent.TextDelta("answer two"), done(Usage.ZERO)),
        )
        val a = agent {
            id = "context-snapshot"
            model { custom(model) }
            memory { custom("recording") { memory } }
        }
        AgentRuntime { dispatcher = StandardTestDispatcher(testScheduler) }.use { runtime ->
            val context = mutableListOf<ModelItem>(ModelItem.system("temporary"))
            val handle = runtime.spawn(a, "one", context = context, thread = ThreadId("thread"))
            context.clear()
            context += ModelItem.system("mutated")
            handle.await()
            runtime.run(a, "two", thread = "thread")
            assertEquals("temporary", model.requests.first().items.first().displayText())
            assertFalse(model.requests.last().items.any { it.displayText() in listOf("temporary", "mutated") })
            assertFalse(memory.items.any { it.displayText() in listOf("temporary", "mutated") })
        }
    }

    @Test
    fun inherited_children_serialize_writes_to_custom_memory() = runTest {
        val memory = RecordingMemory()
        val child = agent {
            id = "child-writer"
            model { custom(FakeLanguageModel(
                listOf(ModelEvent.TextDelta("child one"), done(Usage.ZERO)),
                listOf(ModelEvent.TextDelta("child two"), done(Usage.ZERO)),
            )) }
        }
        val parent = agent {
            id = "parent-writer"
            memory { custom("recording") { memory } }
            model { custom(FakeLanguageModel(
                listOf(ModelEvent.ToolCallCompleted(ToolCall("c1", "child", "{}")), ModelEvent.ToolCallCompleted(ToolCall("c2", "child", "{}")), done(Usage.ZERO)),
                listOf(ModelEvent.TextDelta("parent done"), done(Usage.ZERO)),
            )) }
            tools { tool<NoArgs>("child", "child") { spawnChild(child, "child input").await().text } }
        }
        AgentRuntime { dispatcher = StandardTestDispatcher(testScheduler); maxConcurrency = 2 }.use { runtime ->
            assertEquals("parent done", runtime.run(parent, "question", thread = "shared").text)
            assertEquals(2, memory.items.filterIsInstance<ModelItem.ToolResult>().size)
            assertTrue(memory.items.map { it.displayText() }.containsAll(listOf("child one", "child two")))
        }
    }

    @Test
    fun structured_finalization_cancellation_keeps_partial_json() = runTest {
        val memory = RecordingMemory()
        val paused = CompletableDeferred<Unit>()
        var finishedCount = 0
        val model = FakeLanguageModel(
            ArrayDeque(listOf(
                listOf(ModelEvent.TextDelta("draft"), done(Usage.ZERO)),
                listOf(ModelEvent.TextDelta("{\"value\":"), done(Usage.ZERO)),
            )),
            beforeEmit = { event ->
                if (event is ModelEvent.Finished && ++finishedCount == 2) {
                    paused.complete(Unit)
                    awaitCancellation()
                }
            },
        )
        val a = agent {
            id = "structured-cancel"
            model { custom(model) }
            memory { custom("recording") { memory } }
        }
        AgentRuntime { dispatcher = StandardTestDispatcher(testScheduler) }.use { runtime ->
            val handle = runtime.spawnStructured(a, "question", OutputSpec(buildJsonObject { put("type", "object") }, "Result"), thread = ThreadId("thread"))
            paused.await()
            handle.cancel("stop")
            handle.join()
            assertEquals(listOf("question", "draft", "{\"value\":"), memory.items.map { it.displayText() })
        }
    }
}
