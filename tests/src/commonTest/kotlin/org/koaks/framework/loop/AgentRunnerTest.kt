package org.koaks.framework.loop

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.koaks.framework.model.ModelEvent
import org.koaks.framework.model.ToolCall
import org.koaks.framework.model.Usage
import org.koaks.framework.policy.TerminationReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentRunnerTest {

    private fun agentWith(
        id: String,
        model: FakeLanguageModel,
        toolsBlock: ToolScope.() -> Unit = {},
    ): Agent =
        agent {
            this.id = id
            name = "test"
            model { custom(model) }
            tools(toolsBlock)
            terminateAfter(maxSteps = 5)
        }

    @Test
    fun reasoning_is_streamed_but_dropped_from_message() = runTest {
        val model = FakeLanguageModel(
            listOf(
                ModelEvent.ReasoningDelta("let me "),
                ModelEvent.ReasoningDelta("think"),
                ModelEvent.TextDelta("answer"),
                ModelEvent.Completed(Usage(1, 1, 2)),
            ),
        )
        val a = agentWith("runner-reasoning", model)
        val events = a.stream("hi").toList()

        // Surfaced on the event stream, distinct from assistant text.
        val reasoning = events.filterIsInstance<AgentEvent.ReasoningDelta>().joinToString("") { it.text }
        assertEquals("let me think", reasoning)

        // But never folded into the assistant message text.
        val completed = events.filterIsInstance<AgentEvent.Completed>().single()
        assertEquals("answer", completed.message.text)
    }

    @Test
    fun tee_emits_text_before_tool_call() = runTest {
        // One model step that streams text deltas THEN a completed tool call.
        val model = FakeLanguageModel(
            listOf(
                ModelEvent.TextDelta("Let me "),
                ModelEvent.TextDelta("check. "),
                ModelEvent.ToolCallCompleted(ToolCall("c1", "noop", "{}")),
                ModelEvent.Completed(Usage(1, 1, 2)),
            ),
            // second step: no tool calls -> finish
            listOf(ModelEvent.TextDelta("Done."), ModelEvent.Completed(Usage(1, 1, 2))),
        )
        val a = agentWith("runner-tee", model) {
            tool<NoArgs>(name = "noop", description = "no-op") { "ok" }
        }

        val events = a.stream("hi").toList()

        val firstTextIdx = events.indexOfFirst { it is AgentEvent.TextDelta }
        val toolReqIdx = events.indexOfFirst { it is AgentEvent.ToolCallRequested }
        assertTrue(firstTextIdx >= 0, "expected a TextDelta")
        assertTrue(toolReqIdx >= 0, "expected a ToolCallRequested")
        assertTrue(firstTextIdx < toolReqIdx, "text must be emitted before the tool call (tee)")
        assertTrue(events.any { it is AgentEvent.Completed })
    }

    @Test
    fun tool_not_found_goes_through_failure_channel() = runTest {
        val model = FakeLanguageModel(
            listOf(
                ModelEvent.ToolCallCompleted(ToolCall("c1", "ghost", "{}")),
                ModelEvent.Completed(Usage.ZERO),
            ),
            listOf(ModelEvent.TextDelta("recovered"), ModelEvent.Completed(Usage.ZERO)),
        )
        val a = agentWith("runner-tool-not-found", model) // no tools registered

        val events = a.stream("hi").toList()

        val toolResult = events.filterIsInstance<AgentEvent.ToolResult>().single()
        assertTrue(toolResult.isError, "tool-not-found must be an error result")
        assertTrue(events.any { it is AgentEvent.Failed }, "a Failed event must be surfaced")
        // And it must NOT be fed back as a normal success string.
        assertTrue(toolResult.output.contains("ghost"))
    }

    @Test
    fun phantom_tool_call_delta_is_not_dispatched() = runTest {
        // A model hallucinates a stray tool_calls fragment at a second index: it arrives
        // only as a ToolCallDelta with no id/name, and the decoder never promotes it to a
        // ToolCallCompleted. The accumulator must drop it rather than dispatch a nameless
        // call (which would surface a fabricated `tool not found: ` error).
        val model = FakeLanguageModel(
            listOf(
                ModelEvent.ToolCallDelta(id = "c1", index = 0, nameDelta = "noop", argumentsDelta = "{}"),
                ModelEvent.ToolCallDelta(id = "", index = 1), // phantom: blank id, no name
                ModelEvent.ToolCallCompleted(ToolCall("c1", "noop", "{}")),
                ModelEvent.Completed(Usage.ZERO),
            ),
            listOf(ModelEvent.TextDelta("done"), ModelEvent.Completed(Usage.ZERO)),
        )
        val a = agentWith("runner-phantom-tool", model) {
            tool<NoArgs>(name = "noop", description = "no-op") { "ok" }
        }

        val events = a.stream("hi").toList()

        // Exactly one tool result (the real call); the phantom produced none.
        val toolResults = events.filterIsInstance<AgentEvent.ToolResult>()
        assertEquals(1, toolResults.size, "phantom fragment must not be dispatched")
        assertTrue(!toolResults.single().isError)
        // No fabricated tool-not-found failure.
        assertTrue(events.none { it is AgentEvent.Failed }, "phantom must not surface a Failed event")
        assertTrue(events.any { it is AgentEvent.Completed })
    }

    @Test
    fun finishes_when_no_tool_calls() = runTest {
        val model = FakeLanguageModel(
            listOf(ModelEvent.TextDelta("hello"), ModelEvent.Completed(Usage(2, 3, 5))),
        )
        val a = agentWith("runner-no-tools", model)
        val result = a.run("hi")
        assertEquals("hello", result.text)
        assertEquals(5, result.usage.totalTokens)
        assertTrue(result.isSuccess)
        assertTrue(result is AgentResult.Completed)
    }

    @Test
    fun return_directly_finishes_after_tool() = runTest {
        val model = FakeLanguageModel(
            listOf(
                ModelEvent.ToolCallCompleted(ToolCall("c1", "answer", "{}")),
                ModelEvent.Completed(Usage.ZERO),
            ),
        )
        val a = agentWith("runner-return-directly", model) {
            tool<NoArgs>(name = "answer", description = "final", returnDirectly = true) { "the answer" }
        }
        val result = a.run("hi")
        assertEquals("the answer", result.text)
        assertTrue(result is AgentResult.Completed)
        // Only one model call should have happened (returnDirectly skips the next step).
        assertEquals(1, model.calls)
    }

    @Test
    fun terminate_after_emits_terminated_with_max_steps_reason() = runTest {
        val model = FakeLanguageModel(
            listOf(
                ModelEvent.ToolCallCompleted(ToolCall("c1", "noop", "{}")),
                ModelEvent.Completed(Usage.ZERO),
            ),
            listOf(ModelEvent.TextDelta("should not run"), ModelEvent.Completed(Usage.ZERO)),
        )
        val a = agent {
            id = "agent-2"
            name = "test"
            model { custom(model) }
            tools { tool<NoArgs>(name = "noop", description = "no-op") { "ok" } }
            terminateAfter(maxSteps = 1)
        }

        val events = a.stream("hi").toList()

        val terminated = events.filterIsInstance<AgentEvent.Terminated>().single()
        assertEquals(TerminationReason.MaxSteps(1), terminated.reason)
        assertEquals(1, model.calls)
    }

    @Test
    fun run_returns_terminated_result_with_reason() = runTest {
        val model = FakeLanguageModel(
            listOf(
                ModelEvent.ToolCallCompleted(ToolCall("c1", "noop", "{}")),
                ModelEvent.Completed(Usage.ZERO),
            ),
        )
        val a = agent {
            id = "agent-3"
            name = "test"
            model { custom(model) }
            tools { tool<NoArgs>(name = "noop", description = "no-op") { "ok" } }
            terminateAfter(maxSteps = 1)
        }

        val result = a.run("hi")

        assertTrue(result is AgentResult.Terminated)
        assertEquals(TerminationReason.MaxSteps(1), result.reason)
        assertFalse(result.isSuccess, "a policy-driven termination is not a success")
    }

    @Test
    fun failed_result_preserves_accumulated_usage() = runTest {
        // Step 1 completes a tool call (7 tokens), step 2's model call fails terminally.
        val model = FakeLanguageModel(
            listOf(
                ModelEvent.ToolCallCompleted(ToolCall("c1", "noop", "{}")),
                ModelEvent.Completed(Usage(totalTokens = 7)),
            ),
            listOf(ModelEvent.Failed(org.koaks.framework.model.AgentError.ModelError("boom", retriable = false))),
        )
        val a = agent {
            id = "agent-4"
            name = "test"
            model { custom(model) }
            tools { tool<NoArgs>(name = "noop", description = "no-op") { "ok" } }
            terminateAfter(maxSteps = 10)
        }

        val result = a.run("hi")

        assertTrue(result is AgentResult.Failed)
        assertEquals(7, result.usage.totalTokens, "failure must not drop accumulated usage")
    }
}
