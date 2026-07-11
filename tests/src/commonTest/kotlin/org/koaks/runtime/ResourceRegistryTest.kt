package org.koaks.runtime

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.koaks.framework.loop.Agent
import org.koaks.framework.loop.FakeLanguageModel
import org.koaks.framework.loop.NoArgs
import org.koaks.framework.loop.agent
import org.koaks.framework.loop.tool
import org.koaks.framework.model.ModelEvent
import org.koaks.framework.model.ToolCall
import org.koaks.framework.model.Usage
import org.koaks.runtime.resource.AccessMode
import org.koaks.runtime.resource.withRuntimeResource
import kotlin.test.Test
import kotlin.test.assertEquals

class ResourceRegistryTest {

    // A shared, contended resource — a plain counter with a read-modify-write that yields
    // in the middle to surface races if the critical section were not serialized.
    private object Shared {
        var counter = 0
    }

    private fun incrementingAgent(): Agent = agent {
        name = "writer"
        model {
            custom(
                FakeLanguageModel(
                    listOf(ModelEvent.ToolCallCompleted(ToolCall("c1", "incr", "{}")), ModelEvent.Completed(Usage.ZERO)),
                    listOf(ModelEvent.TextDelta("done"), ModelEvent.Completed(Usage.ZERO)),
                ),
            )
        }
        tools {
            tool<NoArgs>(name = "incr", description = "increment the shared counter") {
                withRuntimeResource("shared:counter", AccessMode.WRITE) {
                    val cur = Shared.counter
                    yield() // interleave point: unsafe without the lock
                    Shared.counter = cur + 1
                }
                "ok"
            }
        }
        terminateAfter(maxSteps = 5)
    }

    @Test
    fun mediated_writes_have_no_lost_updates_under_concurrency() = runTest {
        Shared.counter = 0
        val n = 50
        val runtime = AgentRuntime()
        runtime.use {
            val handles = (0 until n).map { _ -> it.spawn(incrementingAgent(), "go") }
            handles.awaitAll()
        }
        assertEquals(n, Shared.counter)
    }

    @Test
    fun direct_path_runs_block_without_a_runtime() = runTest {
        // No runtime in scope: withRuntimeResource must still run the block (private IO).
        Shared.counter = 0
        incrementingAgent().run("go")
        assertEquals(1, Shared.counter)
    }
}
