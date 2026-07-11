package org.koaks.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.koaks.framework.loop.Agent
import org.koaks.framework.loop.FakeLanguageModel
import org.koaks.framework.loop.NoArgs
import org.koaks.framework.loop.agent
import org.koaks.framework.loop.tool
import org.koaks.framework.model.Message
import org.koaks.framework.model.ModelEvent
import org.koaks.framework.model.ToolCall
import org.koaks.framework.model.Usage
import org.koaks.runtime.acb.AgentId
import org.koaks.runtime.acb.LifecycleState
import org.koaks.runtime.context.ContextScope
import org.koaks.runtime.ipc.RuntimeMessage
import org.koaks.runtime.ipc.receiveMessage
import org.koaks.runtime.resource.spawnChild
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RuntimeKernelHardeningTest {

    private fun sayAgent(name: String, answer: String): Agent = agent {
        this.name = name
        model {
            custom(FakeLanguageModel(listOf(ModelEvent.TextDelta(answer), ModelEvent.Completed(Usage(1, 1, 2)))))
        }
        terminateAfter(maxSteps = 5)
    }

    @Test
    fun spawn_injects_context_refs_into_initial_messages() = runTest {
        val model = FakeLanguageModel(
            listOf(ModelEvent.TextDelta("ok"), ModelEvent.Completed(Usage.ZERO)),
        )
        val a = agent {
            name = "with-ctx"
            model { custom(model) }
            terminateAfter(maxSteps = 5)
        }

        val runtime = AgentRuntime()
        runtime.use {
            val ref = it.context.put(
                listOf(Message.user("shared-fact-1"), Message.assistant("shared-fact-2")),
                scope = ContextScope.GLOBAL,
            )
            it.spawn(a, "user-task", contextRefs = listOf(ref)).await()

            val texts = model.lastRequest!!.messages.map { m -> m.text }
            assertTrue(texts.contains("shared-fact-1"))
            assertTrue(texts.contains("shared-fact-2"))
            assertTrue(texts.contains("user-task"))
            assertTrue(texts.indexOf("shared-fact-1") < texts.indexOf("user-task"))
        }
    }

    @Test
    fun reap_removes_terminal_acbs_but_handle_still_readable() = runTest {
        val runtime = AgentRuntime()
        runtime.use {
            val h = it.spawn(sayAgent("solo", "done"), "hi")
            h.await()
            assertEquals(1, it.agents.size)
            assertEquals(1, it.metrics().finished)

            assertEquals(1, it.reap())
            assertEquals(0, it.agents.size)
            assertNull(it.snapshot(h.id))
            assertEquals(0, it.metrics().total)

            assertEquals(LifecycleState.FINISHED, h.state)
            assertEquals("done", h.await().text)
        }
    }

    @Test
    fun tool_can_spawn_child_with_parent_link() = runTest {
        var childId: AgentId? = null
        val parent = agent {
            name = "parent"
            model {
                custom(
                    FakeLanguageModel(
                        listOf(ModelEvent.ToolCallCompleted(ToolCall("c1", "fork", "{}")), ModelEvent.Completed(Usage.ZERO)),
                        listOf(ModelEvent.TextDelta("parent-done"), ModelEvent.Completed(Usage.ZERO)),
                    ),
                )
            }
            tools {
                tool<NoArgs>(name = "fork", description = "spawn a child") {
                    val child = spawnChild(sayAgent("child", "from-child"), "go")
                    childId = child.id
                    child.await().text
                }
            }
            terminateAfter(maxSteps = 5)
        }

        val runtime = AgentRuntime()
        runtime.use { rt ->
            val h = rt.spawn(parent, "go")
            assertEquals("parent-done", h.await().text)
            val id = childId!!
            val childSnap = rt.snapshot(id)
            assertEquals(h.id, childSnap?.parent)
            assertTrue(h.snapshot.children.contains(id))
        }
    }

    @Test
    fun receive_message_marks_waiting() = runTest {
        val waiter = agent {
            name = "waiter"
            model {
                custom(
                    FakeLanguageModel(
                        listOf(ModelEvent.ToolCallCompleted(ToolCall("c1", "recv", "{}")), ModelEvent.Completed(Usage.ZERO)),
                        listOf(ModelEvent.TextDelta("got-it"), ModelEvent.Completed(Usage.ZERO)),
                    ),
                )
            }
            tools {
                tool<NoArgs>(name = "recv", description = "block on mailbox") {
                    receiveMessage().payload
                }
            }
            terminateAfter(maxSteps = 5)
        }

        val runtime = AgentRuntime { dispatcher = UnconfinedTestDispatcher(testScheduler) }
        runtime.use { rt ->
            val h = rt.spawn(waiter, "go")
            h.updates.first { snap -> snap.state == LifecycleState.WAITING }
            rt.ipc.send(
                RuntimeMessage(
                    id = rt.ipc.nextId(),
                    sender = AgentId(0),
                    receiver = h.id,
                    type = "ping",
                    payload = "hello",
                ),
            )
            assertEquals("got-it", h.await().text)
            assertEquals(LifecycleState.FINISHED, h.state)
        }
    }

    @Test
    fun cancelled_request_still_allows_later_round_trip() = runTest {
        val runtime = AgentRuntime()
        runtime.use { rt ->
            val silent = AgentId(1)
            rt.ipc.mailbox(silent)
            repeat(10) {
                val job = async {
                    rt.ipc.request(
                        RuntimeMessage(
                            id = rt.ipc.nextId(),
                            sender = AgentId(2),
                            receiver = silent,
                            type = "ask",
                            payload = "x",
                        ),
                    )
                }
                job.cancel()
                assertFailsWith<CancellationException> { job.await() }
            }

            val server = AgentId(99)
            val box = rt.ipc.mailbox(server)
            val responder = launch {
                val req = box.receive()
                rt.ipc.reply(req, "pong")
            }
            val response = rt.ipc.request(
                RuntimeMessage(id = rt.ipc.nextId(), sender = AgentId(3), receiver = server, type = "ask", payload = "y"),
            )
            assertEquals("pong", response.payload)
            responder.join()
        }
    }
}
