package org.koaks.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.koaks.framework.loop.Agent
import org.koaks.framework.loop.AgentExecutionContext
import org.koaks.framework.loop.AgentResult
import org.koaks.framework.loop.FakeLanguageModel
import org.koaks.framework.loop.NoArgs
import org.koaks.framework.loop.agent
import org.koaks.framework.loop.tool
import org.koaks.framework.model.ModelEvent
import org.koaks.framework.model.ToolCall
import org.koaks.framework.model.Usage
import org.koaks.runtime.acb.LifecycleState
import org.koaks.runtime.resource.spawnChild
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers the slot-parking mechanism: a runtime instance that blocks awaiting a child
 * releases its scheduler slot for the duration of the wait and re-acquires it after,
 * so `maxConcurrency` bounds instances *actually running* rather than instances
 * admitted. Without it, a parent awaiting a child while holding its only slot would
 * deadlock the scheduler at `maxConcurrency = 1`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RuntimeSlotParkingTest {

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
    fun parent_awaiting_child_does_not_deadlock_at_concurrency_one() = runTest {
        var childText: String? = null
        var parentStateWhileAwaiting: LifecycleState? = null

        val parent = probeAgent("parent") {
            val handle = spawnChild(sayAgent("child", "child-answer"), "hi")
            // The child cannot be admitted unless the parent has released its only slot.
            childText = handle.await().text
            parentStateWhileAwaiting = LifecycleState.RUNNING // reached only if await returned
        }

        val runtime = AgentRuntime {
            maxConcurrency = 1
            dispatcher = UnconfinedTestDispatcher(testScheduler)
        }
        runtime.use {
            // Would hang (runTest reports inactivity) if the parent held its slot across the await.
            val result = it.run(parent, "go")
            assertTrue(result is AgentResult.Completed)
            assertEquals("child-answer", childText)
            assertEquals(LifecycleState.RUNNING, parentStateWhileAwaiting)
            // Both instances finished; nothing left stuck WAITING.
            assertTrue(it.agents.all { a -> a.state == LifecycleState.FINISHED })
        }
    }

    @Test
    fun fan_out_children_respect_concurrency_cap_while_parent_parks() = runTest {
        val mutex = Mutex()
        var current = 0
        var maxObserved = 0
        val release = CompletableDeferred<Unit>()

        suspend fun barrier() {
            mutex.withLock {
                current++
                if (current > maxObserved) maxObserved = current
            }
            release.await()
            mutex.withLock { current-- }
        }

        val parent = probeAgent("parent") {
            val kids = (0 until 3).map { i -> spawnChild(probeAgent("kid$i") { barrier() }, "hi") }
            kids.awaitAll()
        }

        val runtime = AgentRuntime {
            maxConcurrency = 2
            dispatcher = UnconfinedTestDispatcher(testScheduler)
        }
        runtime.use {
            val parentHandle = it.spawn(parent, "go")
            // Parent parked awaiting its children frees exactly one slot; with cap=2 that admits
            // two children at once — never all three (which the "children inherit the parent's
            // permit" alternative would have allowed, ignoring the cap).
            assertEquals(2, maxObserved)
            release.complete(Unit)
            val result = parentHandle.await()
            assertTrue(result is AgentResult.Completed)
            assertEquals(4, it.agents.size)
            assertTrue(it.agents.all { a -> a.state == LifecycleState.FINISHED })
        }
    }

    /**
     * The headline case for branch-aware parking: a single model step returns TWO tool
     * calls, each of which spawns a child and awaits it. Both tool branches block at once.
     * The instance must release its only slot (all branches waiting) so both children can
     * run — a per-instance single-flag design deadlocks here because the second tool never
     * re-parks after the first resumes.
     */
    @Test
    fun two_parallel_tools_each_awaiting_a_child_do_not_deadlock_at_concurrency_one() = runTest {
        val parent = agent {
            name = "parent"
            model {
                custom(
                    FakeLanguageModel(
                        // one step, two tool calls issued together → parallel tool branches
                        listOf(
                            ModelEvent.ToolCallCompleted(ToolCall("c1", "forkA", "{}")),
                            ModelEvent.ToolCallCompleted(ToolCall("c2", "forkB", "{}")),
                            ModelEvent.Completed(Usage.ZERO),
                        ),
                        listOf(ModelEvent.TextDelta("parent-done"), ModelEvent.Completed(Usage.ZERO)),
                    ),
                )
            }
            tools {
                tool<NoArgs>(name = "forkA", description = "spawn+await child A") {
                    spawnChild(sayAgent("childA", "A"), "go").await().text
                }
                tool<NoArgs>(name = "forkB", description = "spawn+await child B") {
                    spawnChild(sayAgent("childB", "B"), "go").await().text
                }
            }
            terminateAfter(maxSteps = 5)
        }

        val runtime = AgentRuntime {
            maxConcurrency = 1
            dispatcher = UnconfinedTestDispatcher(testScheduler)
        }
        runtime.use {
            // Hangs (runTest inactivity) unless both tool branches park the shared slot.
            val result = it.run(parent, "go")
            assertTrue(result is AgentResult.Completed)
            assertEquals("parent-done", result.text)
            assertEquals(3, it.agents.size) // parent + 2 children
            assertTrue(it.agents.all { a -> a.state == LifecycleState.FINISHED })
        }
    }

    /** `join()` must route through the branch too, or a parent that joins a child deadlocks at cap=1. */
    @Test
    fun parent_joining_child_does_not_deadlock_at_concurrency_one() = runTest {
        var joined = false
        val parent = probeAgent("parent") {
            val handle = spawnChild(sayAgent("child", "x"), "go")
            handle.join()
            joined = true
        }
        val runtime = AgentRuntime {
            maxConcurrency = 1
            dispatcher = UnconfinedTestDispatcher(testScheduler)
        }
        runtime.use {
            val result = it.run(parent, "go")
            assertTrue(result is AgentResult.Completed)
            assertTrue(joined)
            assertTrue(it.agents.all { a -> a.state == LifecycleState.FINISHED })
        }
    }

    /**
     * While one tool branch runs and another waits, the instance must stay RUNNING (a
     * branch-aware gate reports RUNNING iff any branch is runnable) — never flip to WAITING
     * just because one branch blocked.
     */
    @Test
    fun instance_is_running_while_one_branch_runs_and_another_waits() = runTest {
        val busyRelease = CompletableDeferred<Unit>()
        val waitingRelease = CompletableDeferred<Unit>()
        val bWaiting = CompletableDeferred<Unit>()

        val parent = agent {
            name = "parent"
            model {
                custom(
                    FakeLanguageModel(
                        listOf(
                            ModelEvent.ToolCallCompleted(ToolCall("c1", "busy", "{}")),
                            ModelEvent.ToolCallCompleted(ToolCall("c2", "block", "{}")),
                            ModelEvent.Completed(Usage.ZERO),
                        ),
                        listOf(ModelEvent.TextDelta("done"), ModelEvent.Completed(Usage.ZERO)),
                    ),
                )
            }
            tools {
                // This test-only barrier is intentionally not routed through the execution
                // context, so branch A remains logically RUNNABLE while we inspect the ACB.
                tool<NoArgs>(name = "busy", description = "runnable branch") {
                    bWaiting.await()
                    busyRelease.await()
                    "a"
                }
                // Signal from inside waiting{}: once bWaiting completes, branch B has already
                // transitioned to WAITING in the activity gate.
                tool<NoArgs>(name = "block", description = "waiting branch") {
                    val exec = currentCoroutineContext()[AgentExecutionContext]!!
                    exec.waiting {
                        bWaiting.complete(Unit)
                        waitingRelease.await()
                    }
                    "b"
                }
            }
            terminateAfter(maxSteps = 5)
        }

        val runtime = AgentRuntime {
            maxConcurrency = 4
            dispatcher = UnconfinedTestDispatcher(testScheduler)
        }
        runtime.use {
            val h = it.spawn(parent, "go")
            bWaiting.await()
            // A runnable, B waiting → instance must be RUNNING, not WAITING.
            assertEquals(LifecycleState.RUNNING, h.state)
            busyRelease.complete(Unit)
            waitingRelease.complete(Unit)
            assertTrue(h.await() is AgentResult.Completed)
        }
    }

    @Test
    fun nested_waits_do_not_resume_the_branch_until_the_outer_wait_finishes() = runTest {
        val childStarted = CompletableDeferred<Unit>()
        val childRelease = CompletableDeferred<Unit>()
        val innerCompleted = CompletableDeferred<Unit>()
        val outerRelease = CompletableDeferred<Unit>()
        val child = probeAgent("child") {
            childStarted.complete(Unit)
            childRelease.await()
        }

        val parent = probeAgent("parent") {
            val exec = currentCoroutineContext()[AgentExecutionContext]!!
            exec.waiting {
                spawnChild(child, "go").await()
                innerCompleted.complete(Unit)
                outerRelease.await()
            }
        }

        val runtime = AgentRuntime {
            maxConcurrency = 1
            dispatcher = UnconfinedTestDispatcher(testScheduler)
        }
        runtime.use {
            val h = it.spawn(parent, "go")
            childStarted.await()
            childRelease.complete(Unit)
            innerCompleted.await()
            assertEquals(LifecycleState.WAITING, h.state)
            outerRelease.complete(Unit)
            assertTrue(h.await() is AgentResult.Completed)
        }
    }

    /**
     * A parent cancelled while parked (slot released, awaiting a child) must not corrupt
     * the permit count: its `park` released exactly once and the subsequent `close` must
     * not double-release. After the in-flight child drains, the permit is fully reclaimed
     * and a fresh instance is admitted at `maxConcurrency = 1`.
     */
    @Test
    fun cancelling_a_waiting_parent_leaves_the_permit_balanced() = runTest {
        val childGate = CompletableDeferred<Unit>() // child parks until released
        val parent = probeAgent("parent") {
            val child = spawnChild(probeAgent("child") { childGate.await() }, "go")
            child.await()
        }
        val runtime = AgentRuntime {
            maxConcurrency = 1
            dispatcher = StandardTestDispatcher(testScheduler)
        }
        runtime.use {
            val h = it.spawn(parent, "go")
            advanceUntilIdle()
            // Parent parked awaiting its child (which now holds the only slot).
            assertEquals(LifecycleState.WAITING, h.state)

            h.cancel("test")
            advanceUntilIdle()

            // Cancellation must not try to unpark the parent behind its still-running child.
            // The parent unwinds promptly, propagates cancellation to the child, and both
            // release/close their leases without external help.
            assertFalse(h.isActive)
            assertEquals(0, it.metrics().running, "agents=${it.agents.map { a -> a.agentName to a.state }}")

            // And the reclaimed permit admits a fresh instance.
            val fresh = it.run(sayAgent("fresh", "ok"), "go")
            assertTrue(fresh is AgentResult.Completed)
            assertEquals("ok", fresh.text)
        }
    }

    @Test
    fun cancelled_cold_waiter_does_not_consume_a_later_permit() = runTest {
        val blockerEntered = CompletableDeferred<Unit>()
        val blockerRelease = CompletableDeferred<Unit>()
        val blocker = probeAgent("blocker") {
            blockerEntered.complete(Unit)
            blockerRelease.await()
        }

        val runtime = AgentRuntime {
            maxConcurrency = 1
            dispatcher = UnconfinedTestDispatcher(testScheduler)
        }
        runtime.use {
            val running = it.spawn(blocker, "go")
            blockerEntered.await()

            val cancelled = it.spawn(sayAgent("cancelled", "never"), "go")
            cancelled.cancel("cancel while queued")
            cancelled.join()

            val fresh = it.spawn(sayAgent("fresh", "ok"), "go")
            blockerRelease.complete(Unit)

            assertTrue(running.await() is AgentResult.Completed)
            assertEquals("ok", fresh.await().text)
        }
    }

    @Test
    fun higher_priority_cold_spawn_precedes_lower_priority_resume() = runTest {
        val resumeLow = CompletableDeferred<Unit>()
        val lowWaiting = CompletableDeferred<Unit>()
        val blockerEntered = CompletableDeferred<Unit>()
        val releaseBlocker = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()

        val low = probeAgent("low") {
            val exec = currentCoroutineContext()[AgentExecutionContext]!!
            exec.waiting {
                lowWaiting.complete(Unit)
                resumeLow.await()
            }
            order += "low"
        }
        val blocker = probeAgent("blocker") {
            blockerEntered.complete(Unit)
            releaseBlocker.await()
        }
        val high = probeAgent("high") { order += "high" }

        val runtime = AgentRuntime {
            maxConcurrency = 1
            dispatcher = UnconfinedTestDispatcher(testScheduler)
        }
        runtime.use {
            val lowHandle = it.spawn(low, "go", priority = 1)
            lowWaiting.await()

            val blockerHandle = it.spawn(blocker, "go", priority = 5)
            blockerEntered.await()
            val highHandle = it.spawn(high, "go", priority = 10)

            // Queue the low-priority resume while the blocker still owns the only slot,
            // then release it with both waiters present.
            resumeLow.complete(Unit)
            releaseBlocker.complete(Unit)

            assertTrue(blockerHandle.await() is AgentResult.Completed)
            assertTrue(highHandle.await() is AgentResult.Completed)
            assertTrue(lowHandle.await() is AgentResult.Completed)
            assertEquals(listOf("high", "low"), order)
        }
    }
}
