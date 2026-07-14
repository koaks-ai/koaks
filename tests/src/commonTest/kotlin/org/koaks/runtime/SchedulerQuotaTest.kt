package org.koaks.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import org.koaks.framework.model.ToolCall
import org.koaks.framework.model.Usage
import org.koaks.framework.policy.TerminationReason
import org.koaks.runtime.acb.LifecycleState
import org.koaks.runtime.resource.quota
import org.koaks.runtime.sched.taskGraph
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SchedulerQuotaTest {

    private fun sayAgent(name: String, answer: String): Agent = agent {
        this.name = name
        model {
            custom(FakeLanguageModel(listOf(ModelEvent.TextDelta(answer), ModelEvent.Completed(Usage(1, 1, 2)))))
        }
        terminateAfter(maxSteps = 5)
    }

    /** An agent that runs [onEnter] once (at its first text delta) then completes. */
    private fun probeAgent(name: String, onEnter: suspend () -> Unit): Agent = agent {
        this.name = name
        model {
            custom(
                FakeLanguageModel(
                    ArrayDeque(listOf(listOf(ModelEvent.TextDelta(name), ModelEvent.Completed(Usage.ZERO)))),
                    beforeEmit = { ev -> if (ev is ModelEvent.TextDelta) onEnter() },
                ),
            )
        }
        terminateAfter(maxSteps = 5)
    }

    @Test
    fun concurrency_cap_is_respected() = runTest {
        val mutex = Mutex()
        var current = 0
        var maxObserved = 0
        val release = CompletableDeferred<Unit>()

        suspend fun enter() {
            mutex.withLock {
                current++
                if (current > maxObserved) maxObserved = current
            }
            release.await()
            mutex.withLock { current-- }
        }

        val runtime = AgentRuntime {
            maxConcurrency = 2
            dispatcher = UnconfinedTestDispatcher(testScheduler)
        }
        runtime.use {
            val handles = (0 until 4).map { i -> it.spawn(probeAgent("a$i") { enter() }, "hi") }
            // With the unconfined test dispatcher, admitted instances have already reached
            // the barrier; only maxConcurrency of them may be inside enter() at once.
            assertEquals(2, maxObserved)
            release.complete(Unit)
            val results = handles.awaitAll()
            assertEquals(4, results.size)
            assertTrue(results.all { r -> r is AgentResult.Completed })
        }
    }

    @Test
    fun priority_admission_order_high_to_low() = runTest {
        val order = mutableListOf<String>()
        val mutex = Mutex()
        val blockerGate = CompletableDeferred<Unit>()

        val runtime = AgentRuntime {
            maxConcurrency = 1
            dispatcher = UnconfinedTestDispatcher(testScheduler)
        }
        runtime.use {
            // Occupies the single slot until released.
            val blocker = it.spawn(probeAgent("blocker") { blockerGate.await() }, "hi")

            // Queue three waiters at different priorities while the slot is busy.
            val low = it.spawn(probeAgent("low") { mutex.withLock { order += "low" } }, "hi", priority = 1)
            val high = it.spawn(probeAgent("high") { mutex.withLock { order += "high" } }, "hi", priority = 5)
            val mid = it.spawn(probeAgent("mid") { mutex.withLock { order += "mid" } }, "hi", priority = 3)

            blockerGate.complete(Unit)
            awaitAll(blocker, low, high, mid)

            assertEquals(listOf("high", "mid", "low"), order)
        }
    }

    @Test
    fun quota_max_tool_calls_terminates_run() = runTest {
        // The model keeps requesting tool calls; no tools are registered, so each becomes
        // an error result and the loop continues — until the quota preempts it.
        val model = FakeLanguageModel(
            listOf(ModelEvent.ToolCallCompleted(ToolCall("c1", "noop", "{}")), ModelEvent.Completed(Usage.ZERO)),
            listOf(
                ModelEvent.TextDelta("current-step"),
                ModelEvent.ToolCallCompleted(ToolCall("c2", "noop", "{}")),
                ModelEvent.Completed(Usage.ZERO),
            ),
            listOf(ModelEvent.ToolCallCompleted(ToolCall("c3", "noop", "{}")), ModelEvent.Completed(Usage.ZERO)),
            listOf(ModelEvent.TextDelta("done"), ModelEvent.Completed(Usage.ZERO)),
        )
        val a = agent {
            name = "greedy"
            model { custom(model) }
            terminateAfter(maxSteps = 10)
        }

        val runtime = AgentRuntime()
        runtime.use {
            val terminal = it.stream(a, "hi", quota = quota { maxToolCalls = 1 }).toList().last()
            assertTrue(terminal is AgentEvent.Terminated)
            val reason = terminal.reason
            assertTrue(reason is TerminationReason.Custom && reason.message.contains("maxToolCalls"))
            assertEquals("current-step", terminal.message.text)
            assertEquals(LifecycleState.CANCELLED, it.agents.single().state)
        }
    }

    @Test
    fun quota_wall_clock_times_out() = runTest {
        val neverGate = CompletableDeferred<Unit>() // never completed
        val stuck = probeAgent("stuck") { neverGate.await() }

        val runtime = AgentRuntime { dispatcher = StandardTestDispatcher(testScheduler) }
        runtime.use {
            val collection = async {
                it.stream(stuck, "hi", quota = quota { wallClockMillis = 50 }).toList()
            }
            advanceUntilIdle()
            val terminal = collection.await().last()
            assertTrue(terminal is AgentEvent.Failed)
            assertTrue(terminal.error is AgentError.Timeout)
            assertEquals(LifecycleState.FAILED, it.agents.single().state)
        }
    }

    @Test
    fun task_graph_runs_dependencies_before_dependents() = runTest {
        var bInput: String? = null
        val graph = taskGraph {
            task("A", sayAgent("A", "RA"), input = "go")
            task("B", sayAgent("B", "RB"), dependsOn = listOf("A")) { deps ->
                bInput = deps.getValue("A").text
                "use ${deps.getValue("A").text}"
            }
        }

        val runtime = AgentRuntime()
        runtime.use {
            val results = it.submit(graph)
            assertEquals("RA", results.getValue("A").text)
            assertEquals("RB", results.getValue("B").text)
            assertEquals("RA", bInput)
        }
    }

    @Test
    fun cancelling_submit_cancels_running_node_instances() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val graph = taskGraph {
            task(
                id = "blocked",
                agent = probeAgent("blocked") {
                    started.complete(Unit)
                    release.await()
                },
                input = "go",
            )
        }
        val runtime = AgentRuntime { dispatcher = StandardTestDispatcher(testScheduler) }

        runtime.use {
            val submission = async { it.submit(graph) }
            advanceUntilIdle()
            started.await()
            submission.cancelAndJoin()
            advanceUntilIdle()

            assertEquals(LifecycleState.CANCELLED, it.agents.single().state)
        }
    }
}
