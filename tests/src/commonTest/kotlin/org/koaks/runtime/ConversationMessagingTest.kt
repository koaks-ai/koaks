package org.koaks.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import org.koaks.framework.loop.agent
import org.koaks.framework.loop.tool
import org.koaks.framework.loop.NoArgs
import org.koaks.framework.loop.done
import org.koaks.framework.model.*
import org.koaks.framework.middleware.*
import org.koaks.framework.policy.*
import org.koaks.runtime.coordination.*
import org.koaks.runtime.run.RunEventPayload
import org.koaks.runtime.tools.childAgents
import kotlinx.coroutines.flow.toList
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationMessagingTest {
    private class Probe(private val respond: suspend (ModelRequest, Int) -> List<ModelEvent>) : LanguageModel {
        override val capabilities = ModelCapabilities.DEFAULT
        val requests = mutableListOf<ModelRequest>()
        override fun stream(request: ModelRequest) = flow {
            requests += request
            respond(request, requests.size).forEach { emit(it) }
        }
    }

    private fun answer(text: String) = listOf(ModelEvent.TextDelta(text), done(Usage.ZERO))

    @Test fun incoming_during_final_answer_continues_and_closed_handle_rejects() = runTest {
        val release = CompletableDeferred<Unit>()
        val model = Probe { _, n -> if (n == 1) release.await(); answer("answer-$n") }
        val a = agent { id = "worker"; model { custom(model) } }
        AgentRuntime { dispatcher = StandardTestDispatcher(testScheduler) }.use { runtime ->
            val handle = runtime.spawn(a, "start")
            runCurrent()
            assertIs<SendResult.Accepted>(handle.send("second input"))
            release.complete(Unit)
            assertEquals("answer-2", handle.await().text)
            assertEquals(2, model.requests.size)
            assertEquals("second input", model.requests[1].items.filterIsInstance<ModelItem.IncomingMessage>().single().text)
            assertIs<SendResult.Rejected>(handle.send("late"))
            assertSame(handle, runtime.handle(handle.runId))
            val events = handle.events().toList().mapNotNull { it.payload as? RunEventPayload.Message }
            assertEquals(listOf("accepted", "injected"), events.map { it.status })
        }
    }

    @Test fun fifo_capacity_and_step_memory_edits_are_visible_in_same_request() = runTest {
        val model = Probe { _, _ -> answer("done") }
        val a = agent {
            id = "worker"
            model { custom(model) }
            hook { onStep { ctx ->
                if (ctx.stage == StepPhase.BeforeModelRequest) ctx.memory.append(ModelItem.user("hook"))
                StepDecision.Continue
            } }
        }
        AgentRuntime { dispatcher = StandardTestDispatcher(testScheduler); inboxCapacity = 2 }.use { runtime ->
            val handle = runtime.spawn(a, "start")
            assertIs<SendResult.Accepted>(handle.send("one"))
            assertIs<SendResult.Accepted>(handle.send("two"))
            assertIs<SendResult.Rejected>(handle.send("three"))
            handle.await()
            assertEquals(listOf("one", "two"), model.requests.single().items.filterIsInstance<ModelItem.IncomingMessage>().map { it.text })
            assertEquals("hook", model.requests.single().items.filterIsInstance<ModelItem.Message>().last { it.role == Role.USER }.text)
        }
    }

    @Test fun children_are_unique_isolated_and_tools_use_parent_identity() = runTest {
        lateinit var context: AgentContext
        val childModel = Probe { _, _ -> answer("child") }
        val child = agent { id = "child"; summary = "default summary"; model { custom(childModel) } }
        val parentModel = Probe { _, _ ->
            context = requireNotNull(currentAgentContext())
            val first = context.spawn(child, "child input", summary = "specific task")
            assertFailsWith<IllegalStateException> { context.spawn(child, "duplicate") }
            assertNotEquals(context.threadId, first.threadId)
            assertEquals("specific task", context.listChildren().single().summary)
            assertIs<SendResult.Accepted>(context.sendTo(child.id, "parent message"))
            first.await()
            val second = context.spawn(child, "second run")
            second.await()
            answer("parent")
        }
        val parent = agent { id = "parent"; model { custom(parentModel) }; tools { childAgents() } }
        AgentRuntime { dispatcher = StandardTestDispatcher(testScheduler); maxConcurrency = 1 }.use { runtime ->
            runtime.run(parent, "private parent input")
            val request = childModel.requests.first()
            assertFalse(request.items.any { it.displayText().contains("private parent input") })
            assertEquals(context.runId.value.toString(), request.items.filterIsInstance<ModelItem.IncomingMessage>().single().sender)
            assertEquals(setOf("list_children", "send_to_child"), parent.tools.names())
        }
    }

    @Test fun hook_suspension_releases_slot_and_resume_does_not_repeat_hook() = runTest {
        var calls = 0
        val model = Probe { _, _ -> answer("done") }
        val a = agent { id = "paused"; model { custom(model) }; hook { onStep {
            if (it.stage == StepPhase.BeforeModelRequest) { calls++; StepDecision.Suspend("approval") } else StepDecision.Continue
        } } }
        AgentRuntime { dispatcher = StandardTestDispatcher(testScheduler); maxConcurrency = 1 }.use { runtime ->
            val handle = runtime.spawn(a, "start")
            runCurrent()
            assertEquals(1, calls)
            assertTrue(model.requests.isEmpty())
            val other = agent { id = "other"; model { custom(Probe { _, _ -> answer("other") }) } }
            assertEquals("other", runtime.run(other, "go").text)
            handle.resume()
            assertEquals("done", handle.await().text)
            assertEquals(1, calls)
        }
    }

    @Test fun context_budget_checks_final_hook_request_before_model() = runTest {
        val model = Probe { _, _ -> answer("unexpected") }
        val a = agent {
            id = "limited"; model { custom(model) }
            contextBudget = ContextBudget(1) { req -> req.items.size }
            hook { onModelCall { before { it.request.copy(items = it.request.items + ModelItem.user("extra")) } } }
        }
        AgentRuntime { dispatcher = StandardTestDispatcher(testScheduler) }.use { runtime ->
            assertIs<org.koaks.framework.loop.AgentResult.Terminated>(runtime.run(a, "start"))
            assertTrue(model.requests.isEmpty())
        }
    }

    @Test fun token_budget_stops_before_tools_and_reports_queued_messages() = runTest {
        var executed = false
        val responseGate = CompletableDeferred<Unit>()
        val model = Probe { _, _ ->
            responseGate.await()
            listOf(ModelEvent.ToolCallCompleted(ToolCall("call", "danger", "{}")), done(Usage(totalTokens = 10)))
        }
        val a = agent {
            id = "tokens"; model { custom(model) }; runBudget(maxTotalTokens = 10)
            tools { tool<NoArgs>("danger", "side effect", hasSideEffects = true) { executed = true; "ok" } }
        }
        AgentRuntime { dispatcher = StandardTestDispatcher(testScheduler) }.use { runtime ->
            val handle = runtime.spawn(a, "go")
            runCurrent()
            handle.send("still queued")
            responseGate.complete(Unit)
            assertIs<org.koaks.framework.loop.AgentResult.Terminated>(handle.await())
            assertFalse(executed)
            assertEquals(10, handle.snapshot.usage.totalTokens)
            val statuses = handle.events().toList().mapNotNull { (it.payload as? RunEventPayload.Message)?.status }
            assertEquals(listOf("accepted", "unprocessed"), statuses)
        }
    }

    @Test fun memory_failure_retries_same_item_without_duplicate_or_early_model_call() = runTest {
        val stored = mutableListOf<ModelItem>()
        var failed = false
        val memory = object : org.koaks.framework.memory.ThreadMemory {
            override suspend fun load(query: List<ModelItem>) = org.koaks.framework.memory.MemoryView(stored.toList())
            override suspend fun append(item: ModelItem) {
                val index = stored.indexOfFirst { it.ref == item.ref }
                if (index < 0) stored.add(item) else stored[index] = item
                if (item is ModelItem.IncomingMessage && !failed) { failed = true; error("lost write acknowledgement") }
            }
        }
        val model = Probe { _, _ -> answer("done") }
        val a = agent {
            id = "retry-memory"; model { custom(model) }; memory { custom("retry-memory") { memory } }
            onError(ErrorPolicy { _, _ -> Recovery.Retry(maxRetries = 1, delayMs = 0) })
        }
        AgentRuntime { dispatcher = StandardTestDispatcher(testScheduler) }.use { runtime ->
            val handle = runtime.spawn(a, "go", thread = "retry-memory")
            handle.send("message")
            handle.await()
            assertTrue(failed)
            assertEquals(1, stored.filterIsInstance<ModelItem.IncomingMessage>().size)
            assertEquals(1, model.requests.size)
        }
    }

    @Test fun structured_reply_is_buffered_until_pending_input_is_processed() = runTest {
        val gate = CompletableDeferred<Unit>()
        val model = Probe { _, n ->
            if (n == 2) gate.await()
            answer(if (n % 2 == 0) "{\"value\":$n}" else "draft")
        }
        val a = agent { id = "structured"; model { custom(model) } }
        AgentRuntime { dispatcher = StandardTestDispatcher(testScheduler) }.use { runtime ->
            val handle = runtime.spawnStructured(a, "go", org.koaks.framework.loop.OutputSpec(kotlinx.serialization.json.JsonObject(emptyMap()), "result"))
            runCurrent()
            assertEquals(2, model.requests.size)
            handle.send("revise answer")
            gate.complete(Unit)
            assertEquals("{\"value\":4}", handle.await().text)
            val text = handle.events().toList().mapNotNull { ((it.payload as? RunEventPayload.Agent)?.event as? org.koaks.framework.loop.AgentEvent.TextDelta)?.text }.joinToString("")
            assertEquals("{\"value\":4}", text)
            assertEquals(4, handle.snapshot.stepsCompleted)
        }
    }

    @Test fun child_tools_execute_with_current_context() = runTest {
        val child = agent { id = "child"; summary = "inspect code"; model { custom(Probe { _, _ -> answer("done") }) } }
        lateinit var parent: org.koaks.framework.loop.Agent
        val model = Probe { _, _ ->
            val context = requireNotNull(currentAgentContext())
            val childHandle = context.spawn(child, "go")
            val listed = parent.tools.call("list_children", "{}") as org.koaks.framework.tool.ToolOutcome.Success
            assertTrue(listed.output.contains("inspect code"))
            val sent = parent.tools.call("send_to_child", "{\"agentId\":\"child\",\"message\":\"inspect tests\"}") as org.koaks.framework.tool.ToolOutcome.Success
            assertTrue(sent.output.contains("accepted"))
            val rejected = parent.tools.call("send_to_child", "{\"agentId\":\"other\",\"message\":\"no\"}") as org.koaks.framework.tool.ToolOutcome.Success
            assertTrue(rejected.output.contains("rejected"))
            childHandle.await()
            answer("parent")
        }
        parent = agent { id = "parent"; model { custom(model) }; tools { childAgents() } }
        AgentRuntime { dispatcher = StandardTestDispatcher(testScheduler); maxConcurrency = 1 }.use { it.run(parent, "go") }
    }
}
