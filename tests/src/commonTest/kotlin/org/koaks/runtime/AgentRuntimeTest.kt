package org.koaks.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.koaks.framework.loop.Agent
import org.koaks.framework.loop.AgentEvent
import org.koaks.framework.loop.AgentResult
import org.koaks.framework.loop.FakeLanguageModel
import org.koaks.framework.loop.agent
import org.koaks.framework.model.ModelEvent
import org.koaks.framework.model.Usage
import org.koaks.framework.policy.TerminationReason
import org.koaks.runtime.acb.LifecycleState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AgentRuntimeTest {

    private fun sayAgent(name: String, answer: String): Agent = agent {
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
            assertEquals(5, handles.map { h -> h.id }.toSet().size)
            assertEquals(5, it.agents.size)
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
    fun cancel_moves_instance_to_cancelled() = runTest {
        val gate = CompletableDeferred<Unit>()
        val blocked = agent {
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
    fun observe_true_streams_text_deltas_matching_await_text() = runTest {
        withAgentRuntime {
            val h = spawn(streamingAgent("streamer", listOf("Hello, ", "world", "!")), "hi", observe = true)
            val streamed = async {
                h.events.filterIsInstance<AgentEvent.TextDelta>()
                    .toList().joinToString("") { it.text }
            }
            val result = h.await()
            assertTrue(result is AgentResult.Completed)
            assertEquals("Hello, world!", result.text)
            assertEquals(result.text, streamed.await())
        }
    }

    @Test
    fun observe_false_yields_empty_event_stream() = runTest {
        withAgentRuntime {
            // observe defaults to false: await-only, events is an empty flow.
            val h = spawn(streamingAgent("silent", listOf("a", "b")), "hi")
            val result = h.await()
            assertTrue(result is AgentResult.Completed)
            assertEquals("ab", result.text)
            assertEquals(LifecycleState.FINISHED, h.state)
            assertTrue(h.events.toList().isEmpty())
        }
    }

    @Test
    fun late_collection_loses_no_events() = runTest {
        withAgentRuntime {
            // More events than Channel.BUFFERED used to hold: await-first must neither
            // deadlock nor lose events now that observation retains the complete stream.
            val chunks = (0 until 256).map { "$it," }
            val h = spawn(streamingAgent("late", chunks), "hi", observe = true)
            h.await()
            val streamed = h.events.filterIsInstance<AgentEvent.TextDelta>()
                .toList().joinToString("") { it.text }
            assertEquals(chunks.joinToString(""), streamed)
        }
    }

    @Test
    fun events_reject_a_second_collector_instead_of_splitting_output() = runTest {
        withAgentRuntime {
            val h = spawn(streamingAgent("single-consumer", listOf("one", "two")), "hi", observe = true)
            h.await()
            assertTrue(h.events.toList().isNotEmpty())
            assertFailsWith<IllegalStateException> { h.events.toList() }
        }
    }

    @Test
    fun stopping_event_collection_early_does_not_interrupt_the_agent() = runTest {
        withAgentRuntime {
            val chunks = (0 until 256).map { "$it," }
            val h = spawn(streamingAgent("partial-consumer", chunks), "hi", observe = true)

            assertEquals("0,", h.events.filterIsInstance<AgentEvent.TextDelta>().first().text)

            val result = h.await()
            assertTrue(result is AgentResult.Completed)
            assertEquals(chunks.joinToString(""), result.text)
            assertFailsWith<IllegalStateException> { h.events.toList() }
        }
    }

    @Test
    fun cancelling_while_waiting_for_scheduler_emits_terminated() = runTest {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val occupyingAgent = agent {
            name = "occupying"
            model {
                custom(
                    FakeLanguageModel(
                        ArrayDeque(listOf(listOf(ModelEvent.TextDelta("done"), ModelEvent.Completed(Usage.ZERO)))),
                        beforeEmit = {
                            firstStarted.complete(Unit)
                            releaseFirst.await()
                        },
                    ),
                )
            }
            terminateAfter(maxSteps = 5)
        }

        val runtime = AgentRuntime { maxConcurrency = 1 }
        runtime.use {
            val occupying = it.spawn(occupyingAgent, "hold the only slot")
            firstStarted.await()

            val queued = it.spawn(sayAgent("queued", "never starts"), "wait", observe = true)
            val collected = async { queued.events.toList() }
            queued.cancel("cancel while queued")

            assertFailsWith<CancellationException> { queued.await() }
            val events = collected.await()
            val terminal = events.single()
            assertTrue(terminal is AgentEvent.Terminated)
            val reason = terminal.reason
            assertTrue(reason is TerminationReason.Custom)
            assertEquals("cancel while queued", reason.message)

            releaseFirst.complete(Unit)
            assertEquals("done", occupying.await().text)
        }
    }

    @Test
    fun cancelling_before_the_coroutine_body_starts_emits_terminated() = runTest {
        val queuedDispatcher = StandardTestDispatcher(testScheduler)
        val runtime = AgentRuntime { dispatcher = queuedDispatcher }

        runtime.use {
            val h = it.spawn(sayAgent("never-started", "never runs"), "hi", observe = true)
            h.cancel("cancel before start")

            assertFailsWith<CancellationException> { h.await() }
            assertEquals(LifecycleState.CANCELLED, h.state)
            val events = h.events.toList()
            val terminal = events.single()
            assertTrue(terminal is AgentEvent.Terminated)
            val reason = terminal.reason
            assertTrue(reason is TerminationReason.Custom)
            assertEquals("cancel before start", reason.message)
        }
    }

    @Test
    fun cancelling_a_running_instance_ends_events_with_terminated() = runTest {
        val enteredModel = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val blocked = agent {
            name = "running-then-cancel"
            model {
                custom(
                    FakeLanguageModel(
                        ArrayDeque(
                            listOf(
                                listOf(
                                    ModelEvent.TextDelta("partial"),
                                    ModelEvent.Completed(Usage(1, 1, 2)),
                                ),
                            ),
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

        withAgentRuntime {
            val h = spawn(blocked, "hi", observe = true)
            val collected = async { h.events.toList() }
            enteredModel.await()
            h.cancel("operator stop")

            assertFailsWith<CancellationException> { h.await() }
            val events = collected.await()
            assertEquals(LifecycleState.CANCELLED, h.state)
            val terminal = events.last()
            assertTrue(terminal is AgentEvent.Terminated)
            val reason = terminal.reason
            assertTrue(reason is TerminationReason.Custom)
            assertEquals("operator stop", reason.message)
            release.complete(Unit)
        }
    }
}
