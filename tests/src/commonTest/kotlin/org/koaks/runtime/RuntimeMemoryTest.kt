package org.koaks.runtime

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.koaks.framework.loop.OutputSpec
import org.koaks.framework.loop.agent
import org.koaks.framework.memory.ThreadId
import org.koaks.framework.memory.ThreadMemory
import org.koaks.framework.memory.WindowMemory
import org.koaks.framework.model.Message
import org.koaks.framework.model.ModelEvent
import org.koaks.framework.model.Role
import org.koaks.framework.model.Usage
import org.koaks.framework.loop.FakeLanguageModel
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Memory is owned at the Runtime turn boundary, so every execution shape shares the
 * same load/commit semantics.
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
            id = "agent-49"
            name = "t"
            model { custom(model) }
            memory { custom("test-memory") { mem } }
        }

        withAgentRuntime {
            a.runIn(this, "q1", thread = "user-1")
            a.runIn(this, "q2", thread = "user-1")
        }

        val history = mem.load(Message.user(""))
        assertEquals(listOf("q1", "q2"), history.filter { it.role == Role.USER }.map { it.text })
        assertEquals(2, history.count { it.role == Role.ASSISTANT })
    }

    @Test
    fun distinct_threads_do_not_bleed_through_runtime() = runTest {
        val model = FakeLanguageModel(
            listOf(ModelEvent.TextDelta("a1"), ModelEvent.Completed(Usage.ZERO)),
            listOf(ModelEvent.TextDelta("a2"), ModelEvent.Completed(Usage.ZERO)),
        )
        val memories = mutableMapOf<ThreadId, WindowMemory>()
        val a = agent {
            id = "agent-50"
            name = "t"
            model { custom(model) }
            memory {
                custom("test-memory") { thread ->
                    WindowMemory(maxMessages = 50).also { memories[thread] = it }
                }
            }
        }

        withAgentRuntime {
            a.runIn(this, "from alice", thread = "alice")
            a.runIn(this, "from bob", thread = "bob")
        }

        assertEquals(listOf("from alice"), memories.getValue(ThreadId("alice")).load(Message.user("")).filter { it.role == Role.USER }.map { it.text })
        assertEquals(listOf("from bob"), memories.getValue(ThreadId("bob")).load(Message.user("")).filter { it.role == Role.USER }.map { it.text })
    }

    @Test
    fun structured_turn_commits_tool_trace_and_final_json_not_internal_draft() = runTest {
        val model = FakeLanguageModel(
            listOf(ModelEvent.TextDelta("internal draft"), ModelEvent.Completed(Usage.ZERO)),
            listOf(ModelEvent.TextDelta("{\"value\":1}"), ModelEvent.Completed(Usage.ZERO)),
        )
        val mem = WindowMemory(maxMessages = 50)
        val agent = agent {
            id = "structured-memory"
            model { custom(model) }
            memory { custom("structured-memory") { mem } }
        }
        val spec = OutputSpec(buildJsonObject { put("type", "object") }, "Result")

        AgentRuntime().use { runtime ->
            runtime.runStructured(agent, "question", spec, thread = "structured-thread")
        }

        val history = mem.load(Message.user(""))
        assertEquals(listOf("question"), history.filter { it.role == Role.USER }.map { it.text })
        assertEquals(listOf("{\"value\":1}"), history.filter { it.role == Role.ASSISTANT }.map { it.text })
    }

    @Test
    fun memory_none_explicitly_opts_out_of_runtime_default_memory() = runTest {
        val model = FakeLanguageModel(
            listOf(ModelEvent.TextDelta("a1"), ModelEvent.Completed(Usage.ZERO)),
            listOf(ModelEvent.TextDelta("a2"), ModelEvent.Completed(Usage.ZERO)),
        )
        val agent = agent {
            id = "no-memory-agent"
            model { custom(model) }
            memory { none() }
        }

        AgentRuntime().use { runtime ->
            runtime.run(agent, "q1", thread = "no-memory-thread")
            runtime.run(agent, "q2", thread = "no-memory-thread")
        }

        assertEquals(listOf("q2"), model.lastRequest!!.messages.map { it.text })
    }

    @Test
    fun runtime_closes_each_opened_thread_memory() = runTest {
        val closed = CompletableDeferred<Unit>()
        val memory = object : ThreadMemory {
            override suspend fun load(query: Message): List<Message> = emptyList()
            override suspend fun commit(messages: List<Message>, usage: Usage) {}
            override fun close() {
                closed.complete(Unit)
            }
        }
        val agent = agent {
            id = "close-thread-memory"
            model { custom(FakeLanguageModel(listOf(ModelEvent.TextDelta("done"), ModelEvent.Completed(Usage.ZERO)))) }
            memory { custom("close-thread-memory") { memory } }
        }
        val runtime = AgentRuntime()

        runtime.run(agent, "go", thread = "close-thread")
        runtime.close()

        closed.await()
    }
}
