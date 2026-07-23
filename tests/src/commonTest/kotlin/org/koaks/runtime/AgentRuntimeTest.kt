package org.koaks.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import org.koaks.framework.loop.Agent
import org.koaks.framework.loop.AgentEvent
import org.koaks.framework.loop.AgentResult
import org.koaks.framework.loop.FakeLanguageModel
import org.koaks.framework.loop.agent
import org.koaks.framework.loop.tool
import org.koaks.framework.model.AgentError
import org.koaks.framework.model.ModelEvent
import org.koaks.framework.model.ToolCall
import org.koaks.framework.model.Usage
import org.koaks.runtime.acb.AgentHandle
import org.koaks.runtime.acb.LifecycleState
import org.koaks.runtime.context.ContextRef
import org.koaks.runtime.observe.RuntimeEvent
import org.koaks.runtime.resource.ChildConversation
import org.koaks.runtime.resource.ChildFailurePolicy
import org.koaks.runtime.resource.spawnChild
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AgentRuntimeTest {

    @Serializable
    private data object NoArgs

    private fun sayAgent(name: String, answer: String): Agent = agent {
        id = name
        this.name = name
        model {
            custom(
                FakeLanguageModel(
                    listOf(ModelEvent.TextDelta(answer), ModelEvent.Completed(Usage(1, 1, 2))),
                ),
            )
        }
        terminateAfter(maxSteps = 5)
    }

    private fun streamingAgent(name: String, chunks: List<String>): Agent = agent {
        id = name
        this.name = name
        model {
            custom(
                FakeLanguageModel(
                    chunks.map { ModelEvent.TextDelta(it) } + ModelEvent.Completed(Usage(1, chunks.size, 1 + chunks.size)),
                ),
            )
        }
        terminateAfter(maxSteps = 5)
    }

    @Test
    fun spawns_multiple_agents_concurrently_and_tracks_each_acb() = runTest {
        val runtime = AgentRuntime()
        runtime.use {
            val handles = (0 until 5).map { i ->
                it.spawn(sayAgent("a$i", "answer-$i"), "hi")
            }
            val results = handles.awaitAll()

            results.forEachIndexed { i, r ->
                assertTrue(r is AgentResult.Completed)
                assertEquals("answer-$i", r.text)
                assertEquals(2, r.usage.totalTokens)
            }
            // Every ACB is FINISHED with the accumulated usage.
            handles.forEach { h ->
                assertEquals(LifecycleState.FINISHED, h.state)
                assertEquals(2, h.snapshot.usage.totalTokens)
            }
            // Distinct pids.
            assertEquals(5, handles.map { h -> h.runId }.toSet().size)
            assertEquals(5, it.runs.size)
        }
    }

    @Test
    fun lifecycle_reaches_finished_on_success() = runTest {
        withAgentRuntime {
            val h = spawn(sayAgent("solo", "done"), "hi")
            val result = h.await()
            assertTrue(result is AgentResult.Completed)
            assertEquals(LifecycleState.FINISHED, h.state)
            assertEquals(null, h.snapshot.parent) // spawned without a parent
        }
    }

    @Test
    fun spawn_in_extension_targets_the_given_runtime() = runTest {
        withAgentRuntime {
            val agent = sayAgent("ext", "via-extension")
            val h = agent.spawnIn(this, "hi")
            assertEquals("via-extension", h.await().text)
        }
    }

    @Test
    fun run_and_stream_extensions_target_the_given_runtime() = runTest {
        withAgentRuntime {
            val runResult = sayAgent("run-ext", "via-run").runIn(this, "hi")
            val streamed = streamingAgent("stream-ext", listOf("via-", "stream"))
                .streamIn(this, "hi")
                .filterIsInstance<AgentEvent.TextDelta>()
                .toList()
                .joinToString("") { it.text }

            assertEquals("via-run", runResult.text)
            assertEquals("via-stream", streamed)
        }
    }

    @Test
    fun cancel_moves_instance_to_cancelled() = runTest {
        val gate = CompletableDeferred<Unit>()
        val blocked = agent {
            id = "agent-37"
            name = "blocked"
            model {
                custom(
                    FakeLanguageModel(
                        ArrayDeque(listOf(listOf(ModelEvent.TextDelta("never"), ModelEvent.Completed(Usage.ZERO)))),
                        beforeEmit = { gate.await() }, // suspends forever until cancelled
                    ),
                )
            }
            terminateAfter(maxSteps = 5)
        }

        val runtime = AgentRuntime()
        runtime.use {
            val h = it.spawn(blocked, "hi")
            h.cancel("operator stop")
            assertFailsWith<CancellationException> { h.await() }
            h.join()
            assertEquals(LifecycleState.CANCELLED, h.state)
        }
    }

    @Test
    fun direct_run_path_is_unaffected() = runTest {
        // The runtime is opt-in: agent.run() still works with no runtime involved.
        val a = sayAgent("direct", "hello")
        assertEquals("hello", a.run("hi").text)
    }

    @Test
    fun unexpected_instance_failure_marks_the_acb_failed() = runTest {
        val runtime = AgentRuntime()
        runtime.use {
            val h = it.spawn(
                sayAgent("bad-context", "never"),
                "hi",
                contextRefs = listOf(ContextRef("missing")),
            )

            assertFailsWith<IllegalStateException> { h.await() }
            assertEquals(LifecycleState.FAILED, h.state)
            assertTrue(h.snapshot.error is AgentError.ModelError)
        }
    }

    @Test
    fun close_cancels_unstarted_handles_and_rejects_new_instances() = runTest {
        val runtime = AgentRuntime { dispatcher = StandardTestDispatcher(testScheduler) }
        val h = runtime.spawn(sayAgent("never-started", "never"), "hi")

        runtime.close()

        assertFailsWith<CancellationException> { h.await() }
        assertEquals(LifecycleState.CANCELLED, h.state)
        assertFailsWith<IllegalStateException> {
            runtime.spawn(sayAgent("after-close", "never"), "hi")
        }
        assertTrue(runtime.runs.isEmpty())
    }

    @Test
    fun run_returns_the_same_result_shape_as_spawn_await() = runTest {
        withAgentRuntime {
            val runResult = run(sayAgent("foreground", "done"), "hi")
            val spawnResult = spawn(sayAgent("background", "done"), "hi").await()

            assertTrue(runResult is AgentResult.Completed)
            assertTrue(spawnResult is AgentResult.Completed)
            assertEquals(spawnResult.text, runResult.text)
            assertEquals(spawnResult.usage, runResult.usage)
        }
    }

    @Test
    fun stream_forwards_agent_events_and_finishes_the_acb() = runTest {
        withAgentRuntime {
            val events = stream(streamingAgent("streamer", listOf("Hello, ", "world", "!")), "hi").toList()
            val text = events.filterIsInstance<AgentEvent.TextDelta>().joinToString("") { it.text }

            assertEquals("Hello, world!", text)
            assertTrue(events.last() is AgentEvent.Completed)
            assertEquals(LifecycleState.FINISHED, runs.single().state)
        }
    }

    @Test
    fun stream_is_cold_and_each_collection_creates_a_new_instance() = runTest {
        val script = listOf(ModelEvent.TextDelta("again"), ModelEvent.Completed(Usage(1, 1, 2)))
        val repeatable = agent {
            id = "agent-38"
            name = "repeatable"
            model { custom(FakeLanguageModel(script, script)) }
            terminateAfter(maxSteps = 5)
        }
        val runtime = AgentRuntime()
        runtime.use {
            val output = it.stream(repeatable, "hi")
            assertTrue(it.runs.isEmpty())

            repeat(2) {
                val text = output.filterIsInstance<AgentEvent.TextDelta>()
                    .toList().joinToString("") { event -> event.text }
                assertEquals("again", text)
            }
            assertEquals(2, it.runs.size)
            assertTrue(it.runs.all { snapshot -> snapshot.state == LifecycleState.FINISHED })
        }
    }

    @Test
    fun cancelling_run_cancels_the_foreground_instance() = runTest {
        val enteredModel = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val blocked = agent {
            id = "agent-39"
            name = "run-cancelled"
            model {
                custom(
                    FakeLanguageModel(
                        ArrayDeque(
                            listOf(listOf(ModelEvent.TextDelta("never"), ModelEvent.Completed(Usage.ZERO))),
                        ),
                        beforeEmit = {
                            enteredModel.complete(Unit)
                            release.await()
                        },
                    ),
                )
            }
            terminateAfter(maxSteps = 5)
        }

        val runtime = AgentRuntime { dispatcher = StandardTestDispatcher(testScheduler) }
        runtime.use {
            val foreground = async { it.run(blocked, "hi") }
            advanceUntilIdle()
            enteredModel.await()
            foreground.cancelAndJoin()
            advanceUntilIdle()

            assertEquals(LifecycleState.CANCELLED, it.runs.single().state)
        }
    }

    @Test
    fun cancelling_run_cancels_its_descendants() = runTest {
        val childStarted = CompletableDeferred<Unit>()
        val releaseChild = CompletableDeferred<Unit>()
        val child = agent {
            id = "agent-40"
            name = "run-child"
            model {
                custom(
                    FakeLanguageModel(
                        ArrayDeque(
                            listOf(listOf(ModelEvent.TextDelta("child"), ModelEvent.Completed(Usage.ZERO))),
                        ),
                        beforeEmit = {
                            childStarted.complete(Unit)
                            releaseChild.await()
                        },
                    ),
                )
            }
            terminateAfter(maxSteps = 5)
        }
        var childHandle: AgentHandle? = null
        val parent = agent {
            id = "agent-41"
            name = "run-parent"
            model {
                custom(
                    FakeLanguageModel(
                        listOf(ModelEvent.ToolCallCompleted(ToolCall("c1", "fork", "{}")), ModelEvent.Completed(Usage.ZERO)),
                    ),
                )
            }
            tools {
                tool<NoArgs>(name = "fork", description = "spawn a blocked child") {
                    spawnChild(
                        child,
                        "go",
                        failurePolicy = ChildFailurePolicy.CAPTURE,
                        conversation = ChildConversation.Ephemeral,
                    ).also { childHandle = it }.await().text
                }
            }
            terminateAfter(maxSteps = 5)
        }

        val runtime = AgentRuntime { dispatcher = StandardTestDispatcher(testScheduler) }
        runtime.use {
            val foreground = async { it.run(parent, "hi") }
            advanceUntilIdle()
            childStarted.await()
            foreground.cancelAndJoin()
            advanceUntilIdle()

            assertTrue(it.runs.all { snapshot -> snapshot.state == LifecycleState.CANCELLED })
            assertEquals(LifecycleState.CANCELLED, childHandle?.state)
        }
    }

    @Test
    fun taking_only_the_first_stream_event_cancels_the_instance() = runTest {
        val runtime = AgentRuntime { dispatcher = StandardTestDispatcher(testScheduler) }
        runtime.use {
            val collection = async {
                it.stream(streamingAgent("take-one", listOf("one", "two")), "hi")
                    .take(1).toList()
            }
            advanceUntilIdle()
            val events = collection.await()

            assertEquals(1, events.size)
            assertEquals(LifecycleState.CANCELLED, it.runs.single().state)
        }
    }

    @Test
    fun collector_failure_cancels_the_stream_instance() = runTest {
        class CollectorFailure : RuntimeException()

        // Unconfined execution makes the downstream failure race the producer send directly;
        // the instance must still normalize that failure to cancellation.
        val runtime = AgentRuntime { dispatcher = UnconfinedTestDispatcher(testScheduler) }
        runtime.use {
            val collection = async {
                assertFailsWith<CollectorFailure> {
                    it.stream(streamingAgent("collector-failure", listOf("one", "two")), "hi")
                        .collect { throw CollectorFailure() }
                }
            }
            advanceUntilIdle()
            collection.await()

            assertEquals(LifecycleState.CANCELLED, it.runs.single().state)
        }
    }

    @Test
    fun cancelling_the_stream_collector_cancels_the_instance() = runTest {
        val enteredModel = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val blocked = agent {
            id = "agent-42"
            name = "collector-cancelled"
            model {
                custom(
                    FakeLanguageModel(
                        ArrayDeque(
                            listOf(listOf(ModelEvent.TextDelta("never"), ModelEvent.Completed(Usage.ZERO))),
                        ),
                        beforeEmit = {
                            enteredModel.complete(Unit)
                            release.await()
                        },
                    ),
                )
            }
            terminateAfter(maxSteps = 5)
        }

        val runtime = AgentRuntime { dispatcher = StandardTestDispatcher(testScheduler) }
        runtime.use {
            val collection = launch { it.stream(blocked, "hi").collect {} }
            advanceUntilIdle()
            enteredModel.await()
            collection.cancelAndJoin()
            advanceUntilIdle()

            assertEquals(LifecycleState.CANCELLED, it.runs.single().state)
        }
    }

    @Test
    fun closing_the_runtime_propagates_cancellation_to_stream() = runTest {
        val enteredModel = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val blocked = agent {
            id = "agent-43"
            name = "runtime-closed"
            model {
                custom(
                    FakeLanguageModel(
                        ArrayDeque(
                            listOf(listOf(ModelEvent.TextDelta("never"), ModelEvent.Completed(Usage.ZERO))),
                        ),
                        beforeEmit = {
                            enteredModel.complete(Unit)
                            release.await()
                        },
                    ),
                )
            }
            terminateAfter(maxSteps = 5)
        }

        val runtime = AgentRuntime { dispatcher = StandardTestDispatcher(testScheduler) }
        val collection = async { runtime.stream(blocked, "hi").toList() }
        advanceUntilIdle()
        enteredModel.await()
        runtime.close()
        advanceUntilIdle()

        assertFailsWith<CancellationException> { collection.await() }
    }

    @Test
    fun captured_child_failure_does_not_fail_the_parent() = runTest {
        val child = agent {
            id = "isolated-fail-child"
            model {
                custom(
                    FakeLanguageModel(
                        listOf(ModelEvent.Failed(AgentError.ModelError("child boom", false))),
                    ),
                )
            }
        }
        val parent = agent {
            id = "isolated-fail-parent"
            model {
                custom(
                    FakeLanguageModel(
                        listOf(
                            ModelEvent.ToolCallCompleted(ToolCall("c1", "delegate", "{}")),
                            ModelEvent.Completed(Usage.ZERO),
                        ),
                        listOf(ModelEvent.TextDelta("parent-ok"), ModelEvent.Completed(Usage.ZERO)),
                    ),
                )
            }
            tools {
                tool<NoArgs>("delegate", "delegate with isolated failure") {
                    val result = spawnChild(
                        child,
                        "fail",
                        failurePolicy = ChildFailurePolicy.CAPTURE,
                        conversation = ChildConversation.Ephemeral,
                    ).await()
                    "child-status=${result is AgentResult.Failed}"
                }
            }
        }

        val seen = mutableListOf<RuntimeEvent>()
        AgentRuntime { dispatcher = UnconfinedTestDispatcher(testScheduler) }.use { runtime ->
            val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                runtime.events.collect { seen += it }
            }
            val result = runtime.run(parent, "root", thread = "isolated-parent-thread")
            assertTrue(result is AgentResult.Completed)
            assertEquals("parent-ok", result.text)
            assertFalse(seen.any { it is RuntimeEvent.UnhandledChildFailure })
            collector.cancel()
        }
    }

    @Test
    fun unobserved_captured_child_failure_emits_runtime_event() = runTest {
        val child = agent {
            id = "unobserved-fail-child"
            model {
                custom(FakeLanguageModel(listOf(ModelEvent.Failed(AgentError.ModelError("unobserved boom", false)))))
            }
        }
        val parent = agent {
            id = "unobserved-fail-parent"
            model {
                custom(
                    FakeLanguageModel(
                        listOf(
                            ModelEvent.ToolCallCompleted(ToolCall("c1", "delegate", "{}")),
                            ModelEvent.Completed(Usage.ZERO),
                        ),
                        listOf(ModelEvent.TextDelta("parent-ok"), ModelEvent.Completed(Usage.ZERO)),
                    ),
                )
            }
            tools {
                tool<NoArgs>("delegate", "spawn without consuming the result") {
                    spawnChild(
                        child,
                        "fail",
                        failurePolicy = ChildFailurePolicy.CAPTURE,
                        conversation = ChildConversation.Ephemeral,
                    )
                    "spawned"
                }
            }
        }
        val seen = mutableListOf<RuntimeEvent>()

        AgentRuntime { dispatcher = UnconfinedTestDispatcher(testScheduler) }.use { runtime ->
            val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                runtime.events.collect { seen += it }
            }
            val result = runtime.run(parent, "root")

            assertTrue(result is AgentResult.Completed)
            val event = seen.filterIsInstance<RuntimeEvent.UnhandledChildFailure>().single()
            assertEquals("unobserved-fail-child", event.childAgentId.value)
            assertEquals("unobserved boom", event.error.message)
            collector.cancel()
        }
    }

    @Test
    fun ephemeral_child_has_no_thread_binding_and_parks_at_concurrency_one() = runTest {
        var childHandle: AgentHandle? = null
        val child = sayAgent("ephemeral-child", "child-ok")
        val parent = agent {
            id = "ephemeral-parent"
            model {
                custom(
                    FakeLanguageModel(
                        listOf(
                            ModelEvent.ToolCallCompleted(ToolCall("c1", "delegate", "{}")),
                            ModelEvent.Completed(Usage.ZERO),
                        ),
                        listOf(ModelEvent.TextDelta("parent-ok"), ModelEvent.Completed(Usage.ZERO)),
                    ),
                )
            }
            tools {
                tool<NoArgs>("delegate", "run an ephemeral child") {
                    spawnChild(
                        child,
                        "go",
                        failurePolicy = ChildFailurePolicy.CAPTURE,
                        conversation = ChildConversation.Ephemeral,
                    ).also { childHandle = it }.await().text
                }
            }
        }

        AgentRuntime {
            maxConcurrency = 1
            dispatcher = UnconfinedTestDispatcher(testScheduler)
        }.use { runtime ->
            val result = runtime.run(parent, "root", thread = "parent-thread")

            assertTrue(result is AgentResult.Completed)
            assertEquals(null, childHandle?.threadId)
            assertEquals(null, childHandle?.turnId)
            assertEquals(null, childHandle?.snapshot?.threadId)
        }
    }

    @Test
    fun independently_cancelled_capture_child_returns_failed_result_without_cancelling_parent() = runTest {
        val child = agent {
            id = "self-cancelled-capture-child"
            model {
                custom(
                    FakeLanguageModel(
                        ArrayDeque(listOf(listOf(ModelEvent.TextDelta("never"), ModelEvent.Completed(Usage.ZERO)))),
                        beforeEmit = { throw CancellationException("child stopped") },
                    ),
                )
            }
        }
        val parent = agent {
            id = "self-cancelled-capture-parent"
            model {
                custom(
                    FakeLanguageModel(
                        listOf(
                            ModelEvent.ToolCallCompleted(ToolCall("c1", "delegate", "{}")),
                            ModelEvent.Completed(Usage.ZERO),
                        ),
                        listOf(ModelEvent.TextDelta("parent-ok"), ModelEvent.Completed(Usage.ZERO)),
                    ),
                )
            }
            tools {
                tool<NoArgs>("delegate", "await a self-cancelled child") {
                    val result = spawnChild(
                        child,
                        "go",
                        failurePolicy = ChildFailurePolicy.CAPTURE,
                        conversation = ChildConversation.Ephemeral,
                    ).await()
                    "captured=${result is AgentResult.Failed}"
                }
            }
        }

        AgentRuntime { dispatcher = UnconfinedTestDispatcher(testScheduler) }.use { runtime ->
            val result = runtime.run(parent, "root")
            assertTrue(result is AgentResult.Completed)
            assertEquals("parent-ok", result.text)
        }
    }

    @Test
    fun mixed_child_failure_policies_only_propagate_the_propagating_failure() = runTest {
        fun failingChild(id: String, message: String) = agent {
            this.id = id
            model { custom(FakeLanguageModel(listOf(ModelEvent.Failed(AgentError.ModelError(message, false))))) }
        }
        val captured = failingChild("mixed-captured-child", "captured boom")
        val propagating = failingChild("mixed-propagating-child", "propagating boom")
        val parent = agent {
            id = "mixed-policy-parent"
            model {
                custom(
                    FakeLanguageModel(
                        listOf(
                            ModelEvent.ToolCallCompleted(ToolCall("c1", "delegate", "{}")),
                            ModelEvent.Completed(Usage.ZERO),
                        ),
                        listOf(ModelEvent.TextDelta("parent-finished-model"), ModelEvent.Completed(Usage.ZERO)),
                    ),
                )
            }
            tools {
                tool<NoArgs>("delegate", "spawn mixed-policy children") {
                    spawnChild(
                        captured,
                        "go",
                        failurePolicy = ChildFailurePolicy.CAPTURE,
                        conversation = ChildConversation.Ephemeral,
                    ).await()
                    spawnChild(
                        propagating,
                        "go",
                        failurePolicy = ChildFailurePolicy.PROPAGATE,
                        conversation = ChildConversation.Ephemeral,
                    )
                    "spawned"
                }
            }
        }

        AgentRuntime { dispatcher = UnconfinedTestDispatcher(testScheduler) }.use { runtime ->
            val result = runtime.run(parent, "root")
            assertTrue(result is AgentResult.Failed)
            assertContains(result.error.message, "propagating boom")
            assertFalse(result.error.message.contains("captured boom"))
        }
    }
}
