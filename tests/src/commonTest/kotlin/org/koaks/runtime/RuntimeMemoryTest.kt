package org.koaks.runtime

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.koaks.framework.loop.agent
import org.koaks.framework.memory.ThreadId
import org.koaks.framework.memory.WindowMemory
import org.koaks.framework.model.ModelEvent
import org.koaks.framework.model.Role
import org.koaks.framework.model.Usage
import org.koaks.framework.loop.FakeLanguageModel
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Memory now flows through the runtime: a `thread` passed to `runIn` is threaded down to
 * `agent.run(input, thread = ...)`, so a memory-backed agent accumulates history per thread
 * even when driven by the runtime scheduler.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RuntimeMemoryTest {

    @Test
    fun runtime_run_carries_memory_for_a_thread() = runTest {
        val model = FakeLanguageModel(
            listOf(ModelEvent.TextDelta("a1"), ModelEvent.Completed(Usage.ZERO)),
            listOf(ModelEvent.TextDelta("a2"), ModelEvent.Completed(Usage.ZERO)),
        )
        val mem = WindowMemory(maxMessages = 50)
        val a = agent {
            name = "t"
            model { custom(model) }
            memory { custom(mem) }
        }

        withAgentRuntime {
            a.runIn(this, "q1", thread = "user-1")
            a.runIn(this, "q2", thread = "user-1")
        }

        val history = mem.load(ThreadId("user-1"))
        assertEquals(listOf("q1", "q2"), history.filter { it.role == Role.USER }.map { it.text })
        assertEquals(2, history.count { it.role == Role.ASSISTANT })
    }

    @Test
    fun distinct_threads_do_not_bleed_through_runtime() = runTest {
        val model = FakeLanguageModel(
            listOf(ModelEvent.TextDelta("a1"), ModelEvent.Completed(Usage.ZERO)),
            listOf(ModelEvent.TextDelta("a2"), ModelEvent.Completed(Usage.ZERO)),
        )
        val mem = WindowMemory(maxMessages = 50)
        val a = agent {
            name = "t"
            model { custom(model) }
            memory { custom(mem) }
        }

        withAgentRuntime {
            a.runIn(this, "from alice", thread = "alice")
            a.runIn(this, "from bob", thread = "bob")
        }

        assertEquals(listOf("from alice"), mem.load(ThreadId("alice")).filter { it.role == Role.USER }.map { it.text })
        assertEquals(listOf("from bob"), mem.load(ThreadId("bob")).filter { it.role == Role.USER }.map { it.text })
    }
}
