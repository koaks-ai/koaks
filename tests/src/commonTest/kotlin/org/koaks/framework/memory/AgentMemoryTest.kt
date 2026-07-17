package org.koaks.framework.memory

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.koaks.framework.loop.AgentEvent
import org.koaks.framework.loop.FakeLanguageModel
import org.koaks.framework.loop.agent
import org.koaks.framework.model.AgentError
import org.koaks.framework.model.ModelEvent
import org.koaks.framework.model.Role
import org.koaks.framework.model.Usage
import org.koaks.framework.policy.ErrorPolicy
import org.koaks.framework.policy.Recovery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Memory is now exercised directly through `agent.run`/`stream` (the old `AgentThread`
 * entry point is gone). A run with memory configured loads history, replays it, and
 * commits on success — keyed by an explicit `thread`.
 */
class AgentMemoryTest {

    @Test
    fun commits_turn_on_success_and_carries_history() = runTest {
        val model = FakeLanguageModel(
            listOf(ModelEvent.TextDelta("first answer"), ModelEvent.Completed(Usage(1, 1, 2))),
            listOf(ModelEvent.TextDelta("second answer"), ModelEvent.Completed(Usage(1, 1, 2))),
        )
        val mem = WindowMemory(maxMessages = 50)
        val a = agent {
            name = "t"
            model { custom(model) }
            memory { custom(mem) }
        }

        a.run("q1", thread = "user-1")
        val afterFirst = mem.load(ThreadId("user-1"))
        assertEquals(listOf("q1"), afterFirst.filter { it.role == Role.USER }.map { it.text })
        assertTrue(afterFirst.any { it.role == Role.ASSISTANT && it.text == "first answer" })

        a.run("q2", thread = "user-1")
        val afterSecond = mem.load(ThreadId("user-1"))
        assertEquals(listOf("q1", "q2"), afterSecond.filter { it.role == Role.USER }.map { it.text })
    }

    @Test
    fun discards_buffer_when_run_fails() = runTest {
        val model = FakeLanguageModel(
            listOf(ModelEvent.Failed(AgentError.ModelError("boom", retriable = false))),
        )
        val mem = WindowMemory(maxMessages = 50)
        val a = agent {
            name = "t"
            model { custom(model) }
            memory { custom(mem) }
            onError(ErrorPolicy { _, _ -> Recovery.Propagate })
        }

        val events = a.stream("q1", thread = "user-1").toList()
        assertTrue(events.any { it is AgentEvent.Failed })
        assertTrue(events.none { it is AgentEvent.Terminal })

        assertEquals(0, mem.load(ThreadId("user-1")).size)
    }

    @Test
    fun history_is_replayed_across_turns() = runTest {
        val model = FakeLanguageModel(
            listOf(ModelEvent.TextDelta("a1"), ModelEvent.Completed(Usage.ZERO)),
            listOf(ModelEvent.TextDelta("a2"), ModelEvent.Completed(Usage.ZERO)),
        )
        val mem = WindowMemory(maxMessages = 50)
        val a = agent {
            name = "t"
            instructions = "be helpful"
            model { custom(model) }
            memory { custom(mem) }
        }
        a.run("q1", thread = "u")
        a.run("q2", thread = "u")

        val loaded = mem.load(ThreadId("u"))
        assertEquals(2, loaded.count { it.role == Role.USER })
        assertEquals(2, loaded.count { it.role == Role.ASSISTANT })
    }

    @Test
    fun distinct_threads_do_not_bleed_into_each_other() = runTest {
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
        a.run("hello from alice", thread = "alice")
        a.run("hello from bob", thread = "bob")

        assertEquals(listOf("hello from alice"), mem.load(ThreadId("alice")).filter { it.role == Role.USER }.map { it.text })
        assertEquals(listOf("hello from bob"), mem.load(ThreadId("bob")).filter { it.role == Role.USER }.map { it.text })
    }

    @Test
    fun default_thread_is_used_when_none_given() = runTest {
        val model = FakeLanguageModel(
            listOf(ModelEvent.TextDelta("a1"), ModelEvent.Completed(Usage.ZERO)),
        )
        val mem = WindowMemory(maxMessages = 50)
        val a = agent {
            name = "t"
            model { custom(model) }
            memory { custom(mem) }
        }
        a.run("q1") // no thread → ThreadId.DEFAULT (warns once)

        assertEquals(listOf("q1"), mem.load(ThreadId.DEFAULT).filter { it.role == Role.USER }.map { it.text })
    }
}
