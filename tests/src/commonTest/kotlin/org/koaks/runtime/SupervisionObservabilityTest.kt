package org.koaks.runtime

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.koaks.framework.loop.Agent
import org.koaks.framework.loop.AgentEvent
import org.koaks.framework.loop.AgentResult
import org.koaks.framework.loop.FakeLanguageModel
import org.koaks.framework.loop.agent
import org.koaks.framework.model.AgentError
import org.koaks.framework.model.ModelEvent
import org.koaks.framework.model.Usage
import org.koaks.runtime.fault.SupervisionPolicy
import org.koaks.runtime.observe.RuntimeEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SupervisionObservabilityTest {

    private fun sayAgent(name: String, answer: String): Agent = agent {
        id = name
        this.name = name
        model {
            custom(FakeLanguageModel(listOf(ModelEvent.TextDelta(answer), ModelEvent.Completed(Usage(1, 1, 2)))))
        }
        terminateAfter(maxSteps = 5)
    }

    @Test
    fun runtime_emits_lifecycle_events_and_aggregates_metrics() = runTest {
        val seen = mutableListOf<RuntimeEvent>()
        val runtime = AgentRuntime { dispatcher = StandardTestDispatcher(testScheduler) }
        runtime.use {
            val sub = launch(UnconfinedTestDispatcher(testScheduler)) {
                it.events.collect { e -> seen += e }
            }
            val streamed = async { it.stream(sayAgent("m", "hi"), "go").toList() }
            advanceUntilIdle()
            val agentEvents = streamed.await()
            sub.cancel()

            assertTrue(agentEvents.last() is AgentEvent.Completed)
            assertTrue(seen.any { e -> e is RuntimeEvent.Spawned })
            assertTrue(seen.any { e -> e is RuntimeEvent.Running })
            assertTrue(seen.any { e -> e is RuntimeEvent.Finished })

            val metrics = it.metrics()
            assertEquals(1, metrics.finished)
            assertEquals(2, metrics.totalTokens)
        }
    }

    @Test
    fun supervised_run_retries_then_succeeds() = runTest {
        // Attempt 1 fails at the model; attempt 2 (same shared script deque) succeeds.
        val model = FakeLanguageModel(
            listOf(ModelEvent.Failed(AgentError.ModelError("boom", retriable = false))),
            listOf(ModelEvent.TextDelta("ok"), ModelEvent.Completed(Usage.ZERO)),
        )
        val flaky = agent {
            id = "agent-59"
            name = "flaky"
            model { custom(model) }
            terminateAfter(maxSteps = 5)
        }

        val runtime = AgentRuntime { dispatcher = StandardTestDispatcher(testScheduler) }
        runtime.use {
            val seen = mutableListOf<RuntimeEvent>()
            val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                it.events.collect { event -> seen += event }
            }
            val h = it.spawnSupervised(
                flaky,
                "go",
                SupervisionPolicy(maxRetries = 2, initialBackoffMillis = 10),
                thread = org.koaks.framework.memory.ThreadId("supervised-thread"),
            )
            advanceUntilIdle()
            val result = h.await()
            assertTrue(result is AgentResult.Completed)
            assertEquals("ok", result.text)
            assertEquals(2, model.calls)
            val retry = seen.filterIsInstance<RuntimeEvent.Retrying>().single()
            assertEquals(flaky.id, retry.agentId)
            assertEquals(org.koaks.framework.memory.ThreadId("supervised-thread"), retry.threadId)
            assertTrue(retry.runId != null)
            assertTrue(retry.turnId != null)
            collector.cancel()
        }
    }

    @Test
    fun supervised_run_gives_up_after_max_retries() = runTest {
        val model = FakeLanguageModel(
            listOf(ModelEvent.Failed(AgentError.ModelError("boom-1", retriable = false))),
            listOf(ModelEvent.Failed(AgentError.ModelError("boom-2", retriable = false))),
        )
        val broken = agent {
            id = "agent-60"
            name = "broken"
            model { custom(model) }
            terminateAfter(maxSteps = 5)
        }

        val runtime = AgentRuntime { dispatcher = StandardTestDispatcher(testScheduler) }
        runtime.use {
            val h = it.spawnSupervised(broken, "go", SupervisionPolicy(maxRetries = 1, initialBackoffMillis = 10))
            advanceUntilIdle()
            val result = h.await()
            assertTrue(result is AgentResult.Failed)
            assertEquals(2, model.calls) // initial + 1 retry
        }
    }
}
