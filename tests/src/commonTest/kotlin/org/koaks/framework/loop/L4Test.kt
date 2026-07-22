package org.koaks.framework.loop

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.koaks.framework.middleware.Guardrail
import org.koaks.framework.model.AgentError
import org.koaks.framework.model.ModelEvent
import org.koaks.framework.model.ToolCall
import org.koaks.framework.model.Usage
import org.koaks.framework.policy.ErrorPolicy
import org.koaks.framework.policy.Recovery
import org.koaks.framework.policy.TerminationReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class L4Test {

    @Test
    fun retry_reruns_step_before_any_text() = runTest {
        // First attempt fails pre-text; second attempt succeeds.
        val model = FakeLanguageModel(
            listOf(ModelEvent.Failed(AgentError.ModelError("transient", retriable = true))),
            listOf(ModelEvent.TextDelta("recovered"), ModelEvent.Completed(Usage.ZERO)),
        )
        val a = agent {
            id = "agent-21"
            name = "t"
            model { custom(model) }
            onError(ErrorPolicy.retryRetriable(maxRetries = 2, delayMs = 0))
        }
        val result = a.run("hi")
        assertEquals("recovered", result.text)
        assertEquals(2, model.calls)
    }

    @Test
    fun substitute_continues_with_replacement_message() = runTest {
        val model = FakeLanguageModel(
            listOf(ModelEvent.Failed(AgentError.ModelError("boom", retriable = false))),
            listOf(ModelEvent.TextDelta("after substitute"), ModelEvent.Completed(Usage.ZERO)),
        )
        val a = agent {
            id = "agent-22"
            name = "t"
            model { custom(model) }
            onError(ErrorPolicy.substituteOnError(org.koaks.framework.model.Message.user("try again")))
        }
        val result = a.run("hi")
        assertEquals("after substitute", result.text)
    }

    @Test
    fun run_budget_caps_total_steps() = runTest {
        // A model that always wants another tool call would loop forever; RunBudget stops it.
        val scripts = ArrayDeque((1..20).map {
            listOf<ModelEvent>(
                ModelEvent.ToolCallCompleted(ToolCall("c$it", "noop", "{}")),
                ModelEvent.Completed(Usage.ZERO),
            )
        })
        val model = FakeLanguageModel(scripts)
        val a = agent {
            id = "agent-23"
            name = "t"
            model { custom(model) }
            tools { tool<NoArgs>(name = "noop", description = "noop") { "ok" } }
            terminate(org.koaks.framework.policy.TerminationPolicy.maxSteps(100))
            runBudget(maxTotalSteps = 3)
        }
        val events = a.stream("go").toList()
        val terminated = events.filterIsInstance<AgentEvent.Terminated>().single()
        assertEquals(TerminationReason.RunBudgetSteps(3), terminated.reason)
        // globalStep increments per assistant message; budget of 3 stops the runaway.
        assertTrue(model.calls <= 4, "RunBudget must stop the runaway loop, got ${model.calls} calls")
    }

    @Test
    fun guardrail_blocks_tool_call() = runTest {
        val model = FakeLanguageModel(
            listOf(
                ModelEvent.ToolCallCompleted(ToolCall("c1", "danger", "{}")),
                ModelEvent.Completed(Usage.ZERO),
            ),
            listOf(ModelEvent.TextDelta("done"), ModelEvent.Completed(Usage.ZERO)),
        )
        val a = agent {
            id = "agent-24"
            name = "t"
            model { custom(model) }
            tools { tool<NoArgs>(name = "danger", description = "danger") { "executed!" } }
            install(Guardrail { if (it.call.name == "danger") "not allowed" else null })
            terminateAfter(maxSteps = 5)
        }
        val events = a.stream("go").toList()
        val toolResult = events.filterIsInstance<AgentEvent.ToolResult>().single()
        assertTrue(toolResult.isError)
        assertTrue(toolResult.output.contains("blocked"))
        // The tool body must NOT have run.
        assertTrue(!toolResult.output.contains("executed!"))
    }
}
