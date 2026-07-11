package org.koaks.runtime

import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.koaks.runtime.acb.AgentId
import org.koaks.runtime.context.ContextRef
import org.koaks.runtime.ipc.RuntimeMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class IpcTest {

    @Test
    fun mailbox_delivers_point_to_point() = runTest {
        val runtime = AgentRuntime()
        runtime.use {
            val to = AgentId(42)
            val box = it.ipc.mailbox(to)
            it.ipc.send(RuntimeMessage(id = it.ipc.nextId(), sender = AgentId(1), receiver = to, type = "ping", payload = "hello"))
            val got = box.receive()
            assertEquals("hello", got.payload)
            assertEquals("ping", got.type)
        }
    }

    @Test
    fun request_response_round_trips() = runTest {
        val runtime = AgentRuntime()
        runtime.use {
            val hub = it.ipc
            val server = AgentId(100)
            val serverBox = hub.mailbox(server)

            val responder = launch {
                val req = serverBox.receive()
                hub.reply(req, "pong:${req.payload}")
            }

            val response = hub.request(
                RuntimeMessage(id = hub.nextId(), sender = AgentId(101), receiver = server, type = "ask", payload = "x"),
            )
            assertEquals("pong:x", response.payload)
            assertEquals("ask.reply", response.type)
            responder.join()
        }
    }

    @Test
    fun pub_sub_delivers_to_subscribers() = runTest {
        val runtime = AgentRuntime()
        runtime.use {
            val hub = it.ipc
            val collected = launch(UnconfinedTestDispatcher(testScheduler)) {
                hub.subscribe("news").take(2).toList()
            }
            // Subscriber is active (unconfined) before we publish.
            hub.publish("news", RuntimeMessage(id = hub.nextId(), sender = null, receiver = null, type = "n", payload = "a"))
            hub.publish("news", RuntimeMessage(id = hub.nextId(), sender = null, receiver = null, type = "n", payload = "b"))
            collected.join()
            assertTrue(collected.isCompleted)
        }
    }

    @Test
    fun messages_carry_context_refs_not_copies() = runTest {
        // Large context is referenced, not embedded in the payload.
        val runtime = AgentRuntime()
        runtime.use {
            val to = AgentId(7)
            val box = it.ipc.mailbox(to)
            val ref = ContextRef("ctx-123")
            it.ipc.send(
                RuntimeMessage(
                    id = it.ipc.nextId(),
                    sender = AgentId(1),
                    receiver = to,
                    type = "handoff",
                    payload = "see attached",
                    contextRefs = listOf(ref),
                ),
            )
            val got = box.receive()
            assertEquals(listOf(ref), got.contextRefs)
            assertTrue(got.payload.length < 50) // payload stays small; context travels by ref
        }
    }
}
