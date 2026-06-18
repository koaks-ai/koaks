package org.koaks.framework.memory

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.koaks.framework.loop.FakeLanguageModel
import org.koaks.framework.loop.AgentEvent
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

class AgentThreadTest {

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
        val thread = a.thread("user-1")

        thread.run("q1")
        // History now holds q1 turn.
        val afterFirst = mem.load(ThreadId("user-1"))
        assertEquals(listOf("q1"), afterFirst.filter { it.role == Role.USER }.map { it.text })
        assertTrue(afterFirst.any { it.role == Role.ASSISTANT && it.text == "first answer" })

        thread.run("q2")
        val afterSecond = mem.load(ThreadId("user-1"))
        // Both user turns persisted, in order.
        assertEquals(listOf("q1", "q2"), afterSecond.filter { it.role == Role.USER }.map { it.text })
    }

    @Test
    fun discards_buffer_when_run_fails() = runTest {
        // The model fails before any text; ErrorPolicy propagates → no terminal event.
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
        val thread = a.thread("user-1")

        val events = thread.stream("q1").toList()
        assertTrue(events.any { it is AgentEvent.Failed })
        assertTrue(events.none { it is AgentEvent.Terminal })

        // Nothing committed — persistent history is untouched.
        assertEquals(0, mem.load(ThreadId("user-1")).size)
    }

    @Test
    fun history_is_replayed_into_the_model_request() = runTest {
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
        val thread = a.thread("u")
        thread.run("q1")
        thread.run("q2")

        // After two turns, stored history has system + 2 user + 2 assistant.
        val loaded = mem.load(ThreadId("u"))
        assertEquals(2, loaded.count { it.role == Role.USER })
        assertEquals(2, loaded.count { it.role == Role.ASSISTANT })
    }
}
