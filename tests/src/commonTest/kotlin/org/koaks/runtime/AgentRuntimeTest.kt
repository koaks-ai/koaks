package org.koaks.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.koaks.framework.loop.Agent
import org.koaks.framework.loop.AgentResult
import org.koaks.framework.loop.FakeLanguageModel
import org.koaks.framework.loop.agent
import org.koaks.framework.model.ModelEvent
import org.koaks.framework.model.Usage
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
}
