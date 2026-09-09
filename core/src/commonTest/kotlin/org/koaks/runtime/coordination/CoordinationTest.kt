package org.koaks.runtime.coordination

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.koaks.framework.loop.AgentId
import org.koaks.framework.model.ModelItem
import org.koaks.runtime.run.RunId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CoordinationTest {
    @Test
    fun point_to_point_is_fifo_and_snapshots_attached_context() = runTest {
        val facade = CoordinationFacade()
        val original = ModelItem.user("original")
        val items = mutableListOf(original)
        val target = RunId(2)
        repeat(3) { facade.send(MessageEnvelope(facade.nextId(), RunId(1), target, "item", "$it", items)) }
        items.clear()
        repeat(3) {
            val message = facade.inbox(target).receive()
            assertEquals("$it", message.payload)
            assertEquals(listOf(original), message.context)
        }
        facade.close()
    }

    @Test
    fun cancellation_and_timeout_remove_pending_requests_and_late_replies_reach_sender() = runTest {
        val inboxes = InboxRegistry()
        val broker = RequestBroker(MessageRouter(inboxes), MessageIdGenerator())
        val sender = RunId(1)
        val receiver = RunId(2)
        val request = async { broker.request(MessageEnvelope(10, sender, receiver, "ask")) }
        val incoming = inboxes.get(receiver).receive()
        assertEquals(1, broker.pendingCount)
        request.cancelAndJoin()
        assertEquals(0, broker.pendingCount)
        broker.reply(incoming, "late", emptyList())
        assertEquals("late", inboxes.get(sender).receive().payload)

        assertFailsWith<CancellationException> {
            withTimeout(10) { broker.request(MessageEnvelope(11, sender, receiver, "timeout")) }
        }
        assertEquals(0, broker.pendingCount)
        inboxes.get(receiver).receive()
        val next = async { broker.request(MessageEnvelope(12, sender, receiver, "next")) }
        broker.reply(inboxes.get(receiver).receive(), "ok", emptyList())
        assertEquals("ok", next.await().payload)
        assertEquals(0, broker.pendingCount)
        broker.close()
        inboxes.close()
    }

    @Test
    fun send_failure_does_not_leave_a_pending_request() = runTest {
        val broker = RequestBroker(MessageRouter(InboxRegistry()), MessageIdGenerator())
        assertFailsWith<IllegalArgumentException> { broker.request(MessageEnvelope(1, null, null, "invalid")) }
        assertEquals(0, broker.pendingCount)
    }

    @Test
    fun closing_cancels_pending_requests_and_prevents_reopening_inboxes() = runTest {
        val facade = CoordinationFacade()
        val receiver = RunId(2)
        val inbox = facade.inbox(receiver)
        val request = async { facade.request(MessageEnvelope(1, RunId(1), receiver, "ask")) }
        inbox.receive()
        facade.close()
        facade.close()
        assertFailsWith<CancellationException> { request.await() }
        assertEquals(emptyList(), inbox.asFlow().toList())
        assertFalse(inbox.trySend(MessageEnvelope(2, null, receiver, "late")))
        assertFailsWith<IllegalStateException> { facade.inbox(receiver) }
        assertFailsWith<IllegalStateException> { facade.publish("topic", MessageEnvelope(3, null, null, "late")) }
    }

    @Test
    fun topics_have_no_replay_and_only_deliver_matching_messages() = runTest {
        val facade = CoordinationFacade()
        fun message(value: String) = MessageEnvelope(facade.nextId(), null, null, "news", value)
        facade.publish("news", message("before subscription"))
        val received = async(UnconfinedTestDispatcher(testScheduler)) { facade.subscribe("news").take(2).toList() }
        facade.publish("other", message("unrelated"))
        facade.publish("news", message("first"))
        facade.publish("news", message("second"))
        assertEquals(listOf("first", "second"), received.await().map { it.payload })
        facade.close()
    }

    @Test
    fun context_methods_use_the_bound_run_identity() = runTest {
        val facade = CoordinationFacade()
        val first = context(RunId(1), facade)
        val second = context(RunId(2), facade)
        withContext(second) {
            first.send(second.runId, "bound", "hello", priority = 3, deadlineMillis = 100)
            val sent = second.receive()
            assertEquals(first.runId, sent.sender)
            assertEquals(3, sent.priority)
            assertEquals(100L, sent.deadlineMillis)
            val reply = async { first.request(second.runId, "ask", priority = 4, deadlineMillis = 200) }
            val incoming = second.receive()
            assertEquals(first.runId, incoming.sender)
            assertEquals(4, incoming.priority)
            assertEquals(200L, incoming.deadlineMillis)
            second.reply(incoming, "pong")
            assertEquals("pong", reply.await().payload)
            val published = async(UnconfinedTestDispatcher(testScheduler)) { second.subscribe("status").first() }
            first.publish("status", "done")
            assertEquals(first.runId, published.await().sender)
            assertTrue(currentAgentContext() === second)
        }
        facade.close()
    }

    private fun context(id: RunId, facade: CoordinationFacade) = AgentContext(
        id, AgentId("agent-${id.value}"), null, null, facade,
        AgentSpawner { _, _, _, _, _, _, _, _ -> error("unused in messaging tests") },
    )
}
