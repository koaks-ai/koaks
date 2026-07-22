package org.koaks.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.koaks.framework.loop.Agent
import org.koaks.framework.loop.AgentResult
import org.koaks.framework.loop.FakeLanguageModel
import org.koaks.framework.loop.NoArgs
import org.koaks.framework.loop.agent
import org.koaks.framework.loop.tool
import org.koaks.framework.memory.ThreadId
import org.koaks.framework.memory.ThreadMemory
import org.koaks.framework.model.Message
import org.koaks.framework.model.ModelEvent
import org.koaks.framework.model.Role
import org.koaks.framework.model.ToolCall
import org.koaks.framework.model.Usage
import org.koaks.runtime.acb.LifecycleState
import org.koaks.runtime.observe.RuntimeEvent
import org.koaks.runtime.resource.spawnChild
import org.koaks.runtime.thread.ThreadMemoryPolicyConflictException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RuntimeThreadingTest {

    @Test
    fun agent_dsl_accepts_a_string_id() {
        val definition = agent {
            id = "agent-7"
            model { custom(oneShotModel("unused")) }
        }

        assertEquals("agent-7", definition.id.value)
    }

    @Test
    fun agent_dsl_requires_an_explicit_stable_id() {
        assertFailsWith<IllegalArgumentException> {
            agent { model { custom(oneShotModel("unused")) } }
        }
    }

    @Test
    fun same_thread_is_fifo_across_spawn_run_and_stream() = runTest {
        val firstStarted = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val thirdStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val releaseSecond = CompletableDeferred<Unit>()
        val first = blockingAgent("fifo-spawn", firstStarted, releaseFirst)
        val second = blockingAgent("fifo-run", secondStarted, releaseSecond)
        val third = announcingAgent("fifo-stream", thirdStarted)
        val dispatcher = StandardTestDispatcher(testScheduler)

        AgentRuntime { this.dispatcher = dispatcher }.use { runtime ->
            val firstHandle = runtime.spawn(first, "one", thread = "fifo-thread")
            runCurrent()
            firstStarted.await()

            val secondResult = async { runtime.run(second, "two", thread = "fifo-thread") }
            runCurrent()
            val thirdEvents = async { runtime.stream(third, "three", thread = "fifo-thread").toList() }
            runCurrent()

            assertFalse(secondStarted.isCompleted)
            assertFalse(thirdStarted.isCompleted)

            releaseFirst.complete(Unit)
            runCurrent()
            secondStarted.await()
            assertFalse(thirdStarted.isCompleted)

            releaseSecond.complete(Unit)
            advanceUntilIdle()
            thirdStarted.await()

            assertEquals("fifo-spawn", firstHandle.await().text)
            assertEquals("fifo-run", secondResult.await().text)
            assertTrue(thirdEvents.await().isNotEmpty())
        }
    }

    @Test
    fun different_threads_run_concurrently() = runTest {
        val firstStarted = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val dispatcher = StandardTestDispatcher(testScheduler)

        AgentRuntime {
            this.dispatcher = dispatcher
            maxConcurrency = 2
        }.use { runtime ->
            val first = runtime.spawn(
                blockingAgent("parallel-a", firstStarted, release),
                "one",
                thread = "thread-a",
            )
            val second = runtime.spawn(
                blockingAgent("parallel-b", secondStarted, release),
                "two",
                thread = "thread-b",
            )

            runCurrent()
            assertTrue(firstStarted.isCompleted)
            assertTrue(secondStarted.isCompleted)

            release.complete(Unit)
            advanceUntilIdle()
            first.await()
            second.await()
        }
    }

    @Test
    fun thread_history_is_shared_across_agents_and_keeps_first_memory_instance() = runTest {
        val primaryMemory = RecordingMemory()
        val unusedMemory = RecordingMemory()
        val firstModel = FakeLanguageModel(
            listOf(ModelEvent.TextDelta("answer-a"), ModelEvent.Completed(Usage.ZERO)),
        )
        val secondModel = FakeLanguageModel(
            listOf(ModelEvent.TextDelta("answer-b"), ModelEvent.Completed(Usage.ZERO)),
        )
        val first = memoryAgent("shared-agent-a", firstModel, "shared-provider", primaryMemory)
        val second = memoryAgent("shared-agent-b", secondModel, "shared-provider", unusedMemory)

        AgentRuntime().use { runtime ->
            runtime.run(first, "question-a", thread = "shared-thread")
            runtime.run(second, "question-b", thread = "shared-thread")

            assertEquals(
                listOf("question-a", "answer-a", "question-b"),
                secondModel.lastRequest!!.messages.map { it.text },
            )
            assertEquals(
                listOf("question-a", "question-b"),
                primaryMemory.load(Message.user("")).filter { it.role == Role.USER }.map { it.text },
            )
            assertEquals(emptyList(), unusedMemory.load(Message.user("")))

            val snapshot = runtime.threadSnapshot(ThreadId("shared-thread"))
            assertEquals(setOf(first.id, second.id), snapshot?.participants)
        }
    }

    @Test
    fun incompatible_memory_provider_is_rejected() = runTest {
        val first = memoryAgent(
            "provider-a-agent",
            oneShotModel("a"),
            "provider-a",
            RecordingMemory(),
        )
        val second = memoryAgent(
            "provider-b-agent",
            oneShotModel("b"),
            "provider-b",
            RecordingMemory(),
        )

        AgentRuntime().use { runtime ->
            runtime.run(first, "one", thread = "provider-thread")
            assertFailsWith<ThreadMemoryPolicyConflictException> {
                runtime.run(second, "two", thread = "provider-thread")
            }
        }
    }

    @Test
    fun commit_failure_marks_run_failed_and_releases_next_thread_turn() = runTest {
        val memory = FailOnceMemory()
        val first = memoryAgent("commit-failure-a", oneShotModel("a"), "fail-once", memory)
        val second = memoryAgent("commit-failure-b", oneShotModel("b"), "fail-once", RecordingMemory())

        AgentRuntime().use { runtime ->
            assertFailsWith<IllegalStateException> {
                runtime.run(first, "one", thread = "commit-failure-thread")
            }
            assertEquals("b", runtime.run(second, "two", thread = "commit-failure-thread").text)
            assertEquals(LifecycleState.FAILED, runtime.runs.single { it.agentId == first.id }.state)
            assertEquals(LifecycleState.FINISHED, runtime.runs.single { it.agentId == second.id }.state)
        }
    }

    @Test
    fun cancelling_a_queued_handle_removes_it_without_loading_memory() = runTest {
        val memory = RecordingMemory()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = memoryAgent(
            "queued-first",
            blockingModel("first", started, release),
            "queue-provider",
            memory,
        )
        val second = memoryAgent(
            "queued-second",
            oneShotModel("second"),
            "queue-provider",
            RecordingMemory(),
        )
        val dispatcher = StandardTestDispatcher(testScheduler)

        AgentRuntime { this.dispatcher = dispatcher }.use { runtime ->
            val active = runtime.spawn(first, "one", thread = "queue-thread")
            runCurrent()
            started.await()
            assertEquals(1, memory.loadCalls)

            val queued = runtime.spawn(second, "two", thread = "queue-thread")
            runCurrent()
            assertEquals(LifecycleState.THREAD_QUEUED, queued.state)

            queued.cancel("not needed")
            runCurrent()
            queued.join()

            assertEquals(1, memory.loadCalls)
            assertEquals(emptyList(), runtime.threadSnapshot(ThreadId("queue-thread"))?.queuedTurns)

            release.complete(Unit)
            advanceUntilIdle()
            active.await()
        }
    }

    @Test
    fun direct_agent_apis_share_the_process_default_runtime() = runTest {
        val runtime = AgentRuntime.default
        val first = simpleAgent("default-runtime-a", "a")
        val second = simpleAgent("default-runtime-b", "b")

        val firstHandle = first.spawn("one")
        val secondHandle = second.spawn("two")

        assertSame(runtime, AgentRuntime.default)
        assertEquals(first.id, firstHandle.agentId)
        assertEquals(second.id, secondHandle.agentId)
        firstHandle.await()
        secondHandle.await()
        assertNotNull(runtime.snapshot(firstHandle.runId))
        assertNotNull(runtime.snapshot(secondHandle.runId))
    }

    @Test
    fun direct_agent_runs_share_default_runtime_thread_history() = runTest {
        val first = simpleAgent("default-thread-agent-a", "remembered")
        val secondModel = oneShotModel("continued")
        val second = agent {
            id = "default-thread-agent-b"
            model { custom(secondModel) }
        }

        first.run("first question", thread = "default-shared-thread")
        second.run("continue", thread = "default-shared-thread")

        assertEquals(
            listOf("first question", "remembered", "continue"),
            secondModel.lastRequest!!.messages.map { it.text },
        )
    }

    @Test
    fun duplicate_agent_id_requires_explicit_replacement() = runTest {
        val original = simpleAgent("replaceable-agent", "old")
        val replacement = simpleAgent("replaceable-agent", "new")

        AgentRuntime().use { runtime ->
            assertEquals("old", runtime.run(original, "go").text)
            assertFailsWith<AgentIdConflictException> { runtime.spawn(replacement, "go") }

            runtime.replaceAgent(replacement)
            assertEquals("new", runtime.run(replacement, "go").text)
        }
    }

    @Test
    fun agent_definition_cannot_be_replaced_while_a_run_is_active() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val original = agent {
            id = "active-replacement"
            model { custom(blockingModel("old", started, release)) }
        }
        val replacement = simpleAgent("active-replacement", "new")
        val dispatcher = StandardTestDispatcher(testScheduler)

        AgentRuntime { this.dispatcher = dispatcher }.use { runtime ->
            val active = runtime.spawn(original, "go")
            runCurrent()
            started.await()

            assertFailsWith<IllegalStateException> { runtime.replaceAgent(replacement) }

            release.complete(Unit)
            advanceUntilIdle()
            active.await()
            runtime.replaceAgent(replacement)
            assertEquals("new", runtime.run(replacement, "go").text)
        }
    }

    @Test
    fun handle_acb_and_runtime_events_expose_all_identity_layers() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val seen = mutableListOf<RuntimeEvent>()

        AgentRuntime { this.dispatcher = dispatcher }.use { runtime ->
            val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                runtime.events.collect { seen += it }
            }
            val agent = simpleAgent("identity-agent", "done")
            val handle = runtime.spawn(agent, "go", thread = "identity-thread")
            advanceUntilIdle()
            handle.await()

            assertEquals(agent.id, handle.agentId)
            assertEquals(ThreadId("identity-thread"), handle.threadId)
            assertNotNull(handle.turnId)
            assertEquals(handle.runId, handle.snapshot.runId)
            assertEquals(handle.agentId, handle.snapshot.agentId)
            assertEquals(handle.threadId, handle.snapshot.threadId)
            assertEquals(handle.turnId, handle.snapshot.turnId)

            val spawned = seen.filterIsInstance<RuntimeEvent.Spawned>().single()
            val running = seen.filterIsInstance<RuntimeEvent.Running>().single()
            val finished = seen.filterIsInstance<RuntimeEvent.Finished>().single()
            listOf(spawned.runId, running.runId, finished.runId).forEach { assertEquals(handle.runId, it) }
            listOf(spawned.agentId, running.agentId, finished.agentId).forEach { assertEquals(agent.id, it) }
            listOf(spawned.threadId, running.threadId, finished.threadId).forEach { assertEquals(handle.threadId, it) }
            listOf(spawned.turnId, running.turnId, finished.turnId).forEach { assertEquals(handle.turnId, it) }
            collector.cancel()
        }
    }

    @Test
    fun cancellation_before_dispatch_still_emits_one_identified_runtime_event() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val seen = mutableListOf<RuntimeEvent>()

        AgentRuntime { this.dispatcher = dispatcher }.use { runtime ->
            val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                runtime.events.collect { seen += it }
            }
            val agent = simpleAgent("pre-dispatch-cancel", "unused")
            val handle = runtime.spawn(agent, "go", thread = "cancel-thread")
            handle.cancel("cancel before dispatcher runs")
            advanceUntilIdle()
            handle.join()

            val cancelled = seen.filterIsInstance<RuntimeEvent.Cancelled>().single()
            assertEquals(handle.runId, cancelled.runId)
            assertEquals(handle.agentId, cancelled.agentId)
            assertEquals(handle.threadId, cancelled.threadId)
            assertEquals(handle.turnId, cancelled.turnId)
            collector.cancel()
        }
    }

    @Test
    fun child_runs_inherit_turn_and_root_waits_before_releasing_thread() = runTest {
        val childStarted = CompletableDeferred<Unit>()
        val releaseChild = CompletableDeferred<Unit>()
        val nextStarted = CompletableDeferred<Unit>()
        val child = agent {
            id = "inherited-child"
            model { custom(blockingModel("child", childStarted, releaseChild)) }
        }
        var childHandle: org.koaks.runtime.acb.AgentHandle? = null
        val parent = agent {
            id = "inherited-parent"
            model {
                custom(
                    FakeLanguageModel(
                        listOf(
                            ModelEvent.ToolCallCompleted(ToolCall("c1", "fork", "{}")),
                            ModelEvent.Completed(Usage.ZERO),
                        ),
                        listOf(ModelEvent.TextDelta("parent"), ModelEvent.Completed(Usage.ZERO)),
                    ),
                )
            }
            tools {
                tool<NoArgs>("fork", "spawn without awaiting") {
                    childHandle = spawnChild(child, "child input")
                    "spawned"
                }
            }
        }
        val next = announcingAgent("after-child", nextStarted)
        val dispatcher = StandardTestDispatcher(testScheduler)

        AgentRuntime { this.dispatcher = dispatcher }.use { runtime ->
            val root = runtime.spawn(parent, "root input", thread = "inherited-thread")
            runCurrent()
            childStarted.await()

            val queued = runtime.spawn(next, "next input", thread = "inherited-thread")
            runCurrent()
            assertEquals(LifecycleState.WAITING, root.state)
            assertEquals(LifecycleState.THREAD_QUEUED, queued.state)
            assertFalse(nextStarted.isCompleted)

            val inherited = childHandle!!
            assertEquals(root.threadId, inherited.threadId)
            assertEquals(root.turnId, inherited.turnId)
            assertNotEquals(root.runId, inherited.runId)

            releaseChild.complete(Unit)
            advanceUntilIdle()
            assertEquals("parent", root.await().text)
            assertTrue(nextStarted.isCompleted)
            queued.await()
        }
    }

    @Test
    fun inherited_child_reuses_the_root_history_snapshot_without_another_memory_load() = runTest {
        val memory = RecordingMemory()
        val seed = memoryAgent("history-seed", oneShotModel("seed-answer"), "shared-history", memory)
        val childModel = oneShotModel("child-answer")
        val child = memoryAgent("history-child", childModel, "shared-history", RecordingMemory())
        val parent = agent {
            id = "history-parent"
            model {
                custom(
                    FakeLanguageModel(
                        listOf(
                            ModelEvent.ToolCallCompleted(ToolCall("c1", "delegate", "{}")),
                            ModelEvent.Completed(Usage.ZERO),
                        ),
                        listOf(ModelEvent.TextDelta("parent-answer"), ModelEvent.Completed(Usage.ZERO)),
                    ),
                )
            }
            memory { custom("shared-history") { RecordingMemory() } }
            tools {
                tool<NoArgs>("delegate", "delegate to a child") {
                    spawnChild(child, "child input").await().text
                }
            }
        }

        AgentRuntime {
            maxConcurrency = 1
            dispatcher = UnconfinedTestDispatcher(testScheduler)
        }.use { runtime ->
            runtime.run(seed, "seed", thread = "history-thread")
            assertEquals(1, memory.loadCalls)

            assertEquals("parent-answer", runtime.run(parent, "root input", thread = "history-thread").text)

            assertEquals(2, memory.loadCalls, "the root of each Turn is the only memory loader")
            assertEquals(
                listOf("seed", "seed-answer", "child input"),
                childModel.lastRequest!!.messages.map { it.text },
            )
        }
    }

    @Test
    fun failed_inherited_child_fails_the_root_and_discards_the_turn() = runTest {
        val memory = RecordingMemory()
        val child = agent {
            id = "failing-inherited-child"
            model {
                custom(FakeLanguageModel(listOf(ModelEvent.Failed(org.koaks.framework.model.AgentError.ModelError("child boom", false)))))
            }
        }
        val parent = agent {
            id = "failing-child-parent"
            model {
                custom(
                    FakeLanguageModel(
                        listOf(
                            ModelEvent.ToolCallCompleted(ToolCall("c1", "fork", "{}")),
                            ModelEvent.Completed(Usage.ZERO),
                        ),
                        listOf(ModelEvent.TextDelta("parent completed"), ModelEvent.Completed(Usage.ZERO)),
                    ),
                )
            }
            memory { custom("failed-child-memory") { memory } }
            tools {
                tool<NoArgs>("fork", "spawn a failing child") {
                    spawnChild(child, "fail")
                    "spawned"
                }
            }
        }

        AgentRuntime().use { runtime ->
            val result = runtime.run(parent, "root", thread = "failed-child-thread")
            assertTrue(result is AgentResult.Failed)
            assertTrue(result.error.message.contains("child run"))
            assertEquals(emptyList(), memory.snapshot())
        }
    }

    @Test
    fun child_can_switch_to_another_thread_and_gets_a_new_turn() = runTest {
        val child = simpleAgent("cross-thread-child", "child-answer")
        var childHandle: org.koaks.runtime.acb.AgentHandle? = null
        val parent = agent {
            id = "cross-thread-parent"
            model {
                custom(
                    FakeLanguageModel(
                        listOf(
                            ModelEvent.ToolCallCompleted(ToolCall("c1", "delegate", "{}")),
                            ModelEvent.Completed(Usage.ZERO),
                        ),
                        listOf(ModelEvent.TextDelta("parent-answer"), ModelEvent.Completed(Usage.ZERO)),
                    ),
                )
            }
            tools {
                tool<NoArgs>("delegate", "delegate to another thread") {
                    spawnChild(child, "child input", thread = "child-thread")
                        .also { childHandle = it }
                        .await()
                        .text
                }
            }
        }

        AgentRuntime {
            maxConcurrency = 1
            dispatcher = UnconfinedTestDispatcher(testScheduler)
        }.use { runtime ->
            val root = runtime.spawn(parent, "root input", thread = "parent-thread")
            assertEquals("parent-answer", root.await().text)

            val switched = childHandle!!
            assertEquals(ThreadId("child-thread"), switched.threadId)
            assertNotEquals(root.turnId, switched.turnId)
            assertEquals(root.runId, switched.snapshot.parent)
        }
    }

    @Test
    fun child_side_effect_is_reported_when_the_root_turn_rolls_back() = runTest {
        val memory = RecordingMemory()
        val child = agent {
            id = "side-effect-child"
            model {
                custom(
                    FakeLanguageModel(
                        listOf(
                            ModelEvent.ToolCallCompleted(ToolCall("charge-1", "charge", "{}")),
                            ModelEvent.Completed(Usage.ZERO),
                        ),
                        listOf(ModelEvent.Failed(org.koaks.framework.model.AgentError.ModelError("after charge", false))),
                    ),
                )
            }
            tools {
                tool<NoArgs>("charge", "side effect", hasSideEffects = true) { "charged" }
            }
        }
        val parent = agent {
            id = "side-effect-parent"
            model {
                custom(
                    FakeLanguageModel(
                        listOf(
                            ModelEvent.ToolCallCompleted(ToolCall("c1", "fork", "{}")),
                            ModelEvent.Completed(Usage.ZERO),
                        ),
                        listOf(ModelEvent.TextDelta("parent completed"), ModelEvent.Completed(Usage.ZERO)),
                    ),
                )
            }
            memory { custom("side-effect-memory") { memory } }
            tools {
                tool<NoArgs>("fork", "spawn side-effect child") {
                    spawnChild(child, "charge")
                    "spawned"
                }
            }
        }
        val seen = mutableListOf<RuntimeEvent>()

        AgentRuntime { dispatcher = UnconfinedTestDispatcher(testScheduler) }.use { runtime ->
            val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                runtime.events.collect { seen += it }
            }
            assertTrue(runtime.run(parent, "root", thread = "side-effect-thread") is AgentResult.Failed)
            assertEquals(1, seen.filterIsInstance<RuntimeEvent.SideEffectRollback>().size)
            assertEquals(emptyList(), memory.snapshot())
            collector.cancel()
        }
    }

    private fun simpleAgent(id: String, answer: String): Agent = agent {
        this.id = id
        model { custom(oneShotModel(answer)) }
    }

    private fun announcingAgent(id: String, started: CompletableDeferred<Unit>): Agent = agent {
        this.id = id
        var announced = false
        model {
            custom(
                FakeLanguageModel(
                    ArrayDeque(listOf(listOf(ModelEvent.TextDelta(id), ModelEvent.Completed(Usage.ZERO)))),
                    beforeEmit = {
                        if (!announced) {
                            announced = true
                            started.complete(Unit)
                        }
                    },
                ),
            )
        }
    }

    private fun blockingAgent(
        id: String,
        started: CompletableDeferred<Unit>,
        release: CompletableDeferred<Unit>,
    ): Agent = agent {
        this.id = id
        model { custom(blockingModel(id, started, release)) }
    }

    private fun blockingModel(
        answer: String,
        started: CompletableDeferred<Unit>,
        release: CompletableDeferred<Unit>,
    ): FakeLanguageModel {
        var announced = false
        return FakeLanguageModel(
            ArrayDeque(listOf(listOf(ModelEvent.TextDelta(answer), ModelEvent.Completed(Usage.ZERO)))),
            beforeEmit = {
                if (!announced) {
                    announced = true
                    started.complete(Unit)
                    release.await()
                }
            },
        )
    }

    private fun oneShotModel(answer: String) = FakeLanguageModel(
        listOf(ModelEvent.TextDelta(answer), ModelEvent.Completed(Usage.ZERO)),
    )

    private fun memoryAgent(
        id: String,
        model: FakeLanguageModel,
        providerId: String,
        memory: ThreadMemory,
    ): Agent = agent {
        this.id = id
        this.model { custom(model) }
        this.memory { custom(providerId) { memory } }
    }

    private class RecordingMemory : ThreadMemory {
        private val mutex = Mutex()
        private val messages = mutableListOf<Message>()
        var loadCalls: Int = 0
            private set

        override suspend fun load(query: Message): List<Message> = mutex.withLock {
            loadCalls++
            messages.toList()
        }

        override suspend fun commit(messages: List<Message>, usage: Usage) {
            mutex.withLock { this.messages.addAll(messages) }
        }

        suspend fun snapshot(): List<Message> = mutex.withLock { messages.toList() }
    }

    private class FailOnceMemory : ThreadMemory {
        private val mutex = Mutex()
        private val messages = mutableListOf<Message>()
        private var shouldFail = true

        override suspend fun load(query: Message): List<Message> = mutex.withLock { messages.toList() }

        override suspend fun commit(messages: List<Message>, usage: Usage) {
            mutex.withLock {
                if (shouldFail) {
                    shouldFail = false
                    throw IllegalStateException("durability failure")
                }
                this.messages.addAll(messages)
            }
        }
    }
}
