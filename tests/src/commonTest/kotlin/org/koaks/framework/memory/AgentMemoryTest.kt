package org.koaks.framework.memory

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.koaks.framework.loop.AgentEvent
import org.koaks.framework.loop.FakeLanguageModel
import org.koaks.framework.loop.agent
import org.koaks.framework.model.AgentError
import org.koaks.framework.loop.done
import org.koaks.framework.loop.fail
import org.koaks.framework.model.ModelEvent
import org.koaks.framework.model.Role
import org.koaks.framework.model.Usage
import org.koaks.framework.policy.ErrorPolicy
import org.koaks.framework.policy.Recovery
import org.koaks.runtime.AgentRuntime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Memory is exercised through Runtime turn boundaries (the old `AgentThread` entry point
 * is gone). A run with memory configured loads history, replays it, and commits on
 * success — keyed by an explicit `thread`.
 */
class AgentMemoryTest {

    @Test
    fun commits_turn_on_success_and_carries_history() = runTest {
        val model = FakeLanguageModel(
            listOf(ModelEvent.TextDelta("first answer"), done(Usage(1, 1, 2))),
            listOf(ModelEvent.TextDelta("second answer"), done(Usage(1, 1, 2))),
        )
        val mem = WindowMemory(maxMessages = 50)
        val a = agent {
            id = "agent-30"
            name = "t"
            model { custom(model) }
            memory { custom("test-memory") { mem } }
        }

        AgentRuntime().use { runtime ->
            runtime.run(a, "q1", thread = "user-1")
            val afterFirst = mem.stored()
            assertEquals(listOf("q1"), afterFirst.filterIsInstance<org.koaks.framework.model.ModelItem.Message>().filter { it.role == Role.USER }.map { it.text })
            assertTrue(afterFirst.filterIsInstance<org.koaks.framework.model.ModelItem.Message>().any { it.role == Role.ASSISTANT && it.text == "first answer" })

            runtime.run(a, "q2", thread = "user-1")
            val afterSecond = mem.stored()
            assertEquals(listOf("q1", "q2"), afterSecond.filterIsInstance<org.koaks.framework.model.ModelItem.Message>().filter { it.role == Role.USER }.map { it.text })
        }
    }

    @Test
    fun discards_buffer_when_run_fails() = runTest {
        val model = FakeLanguageModel(
            listOf(fail(AgentError.ModelError("boom", retriable = false))),
        )
        val mem = WindowMemory(maxMessages = 50, retention = TurnRetention.CompletedOnly)
        val a = agent {
            id = "agent-31"
            name = "t"
            model { custom(model) }
            memory { custom("test-memory") { mem } }
            onError(ErrorPolicy { _, _ -> Recovery.Propagate })
        }

        AgentRuntime().use { runtime ->
            val events = runtime.stream(a, "q1", thread = "user-1").toList()
            assertTrue(events.any { it is AgentEvent.Failed })
            assertTrue(events.none { it is AgentEvent.Terminal })

            assertEquals(0, mem.stored().size)
        }
    }

    @Test
    fun history_is_replayed_across_turns() = runTest {
        val model = FakeLanguageModel(
            listOf(ModelEvent.TextDelta("a1"), done(Usage.ZERO)),
            listOf(ModelEvent.TextDelta("a2"), done(Usage.ZERO)),
        )
        val mem = WindowMemory(maxMessages = 50)
        val a = agent {
            id = "agent-32"
            name = "t"
            instructions = "be helpful"
            model { custom(model) }
            memory { custom("test-memory") { mem } }
        }
        AgentRuntime().use { runtime ->
            runtime.run(a, "q1", thread = "u")
            runtime.run(a, "q2", thread = "u")

            val loaded = mem.stored()
            assertEquals(2, loaded.filterIsInstance<org.koaks.framework.model.ModelItem.Message>().count { it.role == Role.USER })
            assertEquals(2, loaded.filterIsInstance<org.koaks.framework.model.ModelItem.Message>().count { it.role == Role.ASSISTANT })
        }
    }

    @Test
    fun distinct_threads_do_not_bleed_into_each_other() = runTest {
        val model = FakeLanguageModel(
            listOf(ModelEvent.TextDelta("a1"), done(Usage.ZERO)),
            listOf(ModelEvent.TextDelta("a2"), done(Usage.ZERO)),
        )
        val memories = mutableMapOf<ThreadId, WindowMemory>()
        val a = agent {
            id = "agent-33"
            name = "t"
            model { custom(model) }
            memory {
                custom("test-memory") { thread ->
                    WindowMemory(maxMessages = 50).also { memories[thread] = it }
                }
            }
        }
        AgentRuntime().use { runtime ->
            runtime.run(a, "hello from alice", thread = "alice")
            runtime.run(a, "hello from bob", thread = "bob")

            assertEquals(listOf("hello from alice"), memories.getValue(ThreadId("alice")).stored().filterIsInstance<org.koaks.framework.model.ModelItem.Message>().filter { it.role == Role.USER }.map { it.text })
            assertEquals(listOf("hello from bob"), memories.getValue(ThreadId("bob")).stored().filterIsInstance<org.koaks.framework.model.ModelItem.Message>().filter { it.role == Role.USER }.map { it.text })
        }
    }

    @Test
    fun no_thread_means_ephemeral_runtime_run() = runTest {
        val model = FakeLanguageModel(
            listOf(ModelEvent.TextDelta("a1"), done(Usage.ZERO)),
        )
        var opens = 0
        val a = agent {
            id = "agent-34"
            name = "t"
            model { custom(model) }
            memory { custom("test-memory") { opens++; WindowMemory(maxMessages = 50) } }
        }
        AgentRuntime().use { runtime -> runtime.run(a, "q1") }

        assertEquals(0, opens)
    }
}
