package org.koaks.framework.loop

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import org.koaks.framework.middleware.ModelCallPhase
import org.koaks.framework.model.Message
import org.koaks.framework.model.ModelEvent
import org.koaks.framework.model.ToolCall
import org.koaks.framework.model.Usage
import org.koaks.framework.tool.ToolOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HookTest {

    @Serializable
    data class StructuredWeather(val city: String, val tempC: Int)

    @Serializable
    data class ValueInput(val value: String)

    @Test
    fun onModelCall_before_injects_request_without_polluting_state() = runTest {
        val rag = Message.system("RAG context")
        val model = FakeLanguageModel(
            listOf(
                ModelEvent.ToolCallCompleted(ToolCall("c1", "noop", "{}")),
                ModelEvent.Completed(Usage.ZERO),
            ),
            listOf(ModelEvent.TextDelta("done"), ModelEvent.Completed(Usage.ZERO)),
        )
        val a = agent {
            name = "t"
            model { custom(model) }
            tools { tool<NoArgs>(name = "noop", description = "noop") { "ok" } }
            hook {
                onModelCall {
                    before { ctx -> ctx.request.copy(messages = listOf(rag) + ctx.request.messages) }
                }
            }
        }

        val result = a.run("hi")

        assertEquals("done", result.text)
        assertEquals(2, model.requests.size)
        assertEquals(1, model.requests[0].messages.count { it.text == "RAG context" })
        assertEquals(1, model.requests[1].messages.count { it.text == "RAG context" })
    }

    @Test
    fun onModelCall_after_wraps_stream() = runTest {
        val model = FakeLanguageModel(
            listOf(ModelEvent.TextDelta("hello"), ModelEvent.Completed(Usage.ZERO)),
        )
        val a = agent {
            name = "t"
            model { custom(model) }
            hook {
                onModelCall {
                    after { _, stream ->
                        stream.map { event ->
                            if (event is ModelEvent.TextDelta) event.copy(text = event.text.uppercase()) else event
                        }
                    }
                }
            }
        }

        val result = a.run("hi")

        assertEquals("HELLO", result.text)
    }

    @Test
    fun onModelCall_structured_finalization_sets_phase() = runTest {
        val phases = mutableListOf<ModelCallPhase>()
        val model = FakeLanguageModel(
            listOf(ModelEvent.TextDelta("It's warm."), ModelEvent.Completed(Usage.ZERO)),
            listOf(ModelEvent.TextDelta("{\"city\":\"NYC\",\"tempC\":21}"), ModelEvent.Completed(Usage.ZERO)),
        )
        val a = agent {
            name = "t"
            model { custom(model) }
            hook {
                onModelCall {
                    before { ctx ->
                        phases += ctx.phase
                        ctx.request
                    }
                }
            }
        }

        val weather: StructuredWeather = a.run<StructuredWeather>("weather?")

        assertEquals("NYC", weather.city)
        assertEquals(listOf(ModelCallPhase.Normal, ModelCallPhase.StructuredFinalization), phases)
    }

    @Test
    fun onToolCall_before_deny_blocks() = runTest {
        var toolRan = false
        val model = FakeLanguageModel(
            listOf(
                ModelEvent.ToolCallCompleted(ToolCall("c1", "danger", "{}")),
                ModelEvent.Completed(Usage.ZERO),
            ),
            listOf(ModelEvent.TextDelta("done"), ModelEvent.Completed(Usage.ZERO)),
        )
        val a = agent {
            name = "t"
            model { custom(model) }
            tools {
                tool<NoArgs>(name = "danger", description = "danger") {
                    toolRan = true
                    "executed"
                }
            }
            hook {
                onToolCall {
                    before { ctx -> if (ctx.call.name == "danger") Deny("not approved") else Proceed }
                }
            }
        }

        val events = a.stream("go").toList()

        val toolResult = events.filterIsInstance<AgentEvent.ToolResult>().single()
        assertTrue(toolResult.isError)
        assertFalse(toolRan)
    }

    @Test
    fun onToolCall_before_rewrites_arguments_preserves_id() = runTest {
        val model = FakeLanguageModel(
            listOf(
                ModelEvent.ToolCallCompleted(ToolCall("original-id", "alias", "{\"value\":\"old\"}")),
                ModelEvent.Completed(Usage.ZERO),
            ),
            listOf(ModelEvent.TextDelta("done"), ModelEvent.Completed(Usage.ZERO)),
        )
        val a = agent {
            name = "t"
            model { custom(model) }
            tools { tool<ValueInput>(name = "echo", description = "echo") { it.value } }
            hook {
                onToolCall {
                    before { ctx ->
                        ProceedWith(ctx.call.copy(name = "echo", arguments = "{\"value\":\"new\"}"))
                    }
                }
            }
        }

        val events = a.stream("go").toList()

        val toolResult = events.filterIsInstance<AgentEvent.ToolResult>().single()
        assertEquals("original-id", toolResult.callId)
        assertEquals("new", toolResult.output)
        assertFalse(toolResult.isError)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun onToolCall_before_suspends_until_approved() = runTest {
        val approval = CompletableDeferred<Boolean>()
        var toolRan = false
        val model = FakeLanguageModel(
            listOf(
                ModelEvent.ToolCallCompleted(ToolCall("c1", "gated", "{}")),
                ModelEvent.Completed(Usage.ZERO),
            ),
            listOf(ModelEvent.TextDelta("done"), ModelEvent.Completed(Usage.ZERO)),
        )
        val a = agent {
            name = "t"
            model { custom(model) }
            tools {
                tool<NoArgs>(name = "gated", description = "gated") {
                    toolRan = true
                    "ok"
                }
            }
            hook {
                onToolCall {
                    before { if (approval.await()) Proceed else Deny("denied") }
                }
            }
        }

        val deferred = async { a.run("go") }
        runCurrent()
        assertFalse(toolRan)
        assertFalse(deferred.isCompleted)

        approval.complete(true)
        val result = deferred.await()

        assertTrue(toolRan)
        assertEquals("done", result.text)
    }

    @Test
    fun onToolResult_transforms_outcome() = runTest {        val model = FakeLanguageModel(
            listOf(
                ModelEvent.ToolCallCompleted(ToolCall("c1", "noisy", "{}")),
                ModelEvent.Completed(Usage.ZERO),
            ),
            listOf(ModelEvent.TextDelta("done"), ModelEvent.Completed(Usage.ZERO)),
        )
        val a = agent {
            name = "t"
            model { custom(model) }
            tools { tool<NoArgs>(name = "noisy", description = "noisy") { "abcdef" } }
            hook {
                onToolCall {
                    after { _, outcome ->
                        if (outcome is ToolOutcome.Success) outcome.copy(output = outcome.output.take(3)) else outcome
                    }
                }
            }
        }

        val events = a.stream("go").toList()

        val toolResult = events.filterIsInstance<AgentEvent.ToolResult>().single()
        assertNotNull(toolResult)
        assertEquals("abc", toolResult.output)
    }

    @Test
    fun onToolResult_skips_hooks_whose_before_was_denied_earlier() = runTest {
        // hook A denies; hook B's before never runs, so B's after must not run either.
        var bAfterRan = false
        val model = FakeLanguageModel(
            listOf(
                ModelEvent.ToolCallCompleted(ToolCall("c1", "danger", "{}")),
                ModelEvent.Completed(Usage.ZERO),
            ),
            listOf(ModelEvent.TextDelta("done"), ModelEvent.Completed(Usage.ZERO)),
        )
        val hookA = object : org.koaks.framework.middleware.Hook {
            override suspend fun onToolCall(ctx: org.koaks.framework.middleware.ToolContext) =
                org.koaks.framework.middleware.ToolDecision.Deny("nope")
        }
        val hookB = object : org.koaks.framework.middleware.Hook {
            override suspend fun onToolResult(
                ctx: org.koaks.framework.middleware.ToolContext,
                outcome: ToolOutcome,
            ): ToolOutcome {
                bAfterRan = true
                return outcome
            }
        }
        val a = agent {
            name = "t"
            model { custom(model) }
            tools { tool<NoArgs>(name = "danger", description = "danger") { "executed" } }
            install(hookA)
            install(hookB)
        }

        val events = a.stream("go").toList()

        val toolResult = events.filterIsInstance<AgentEvent.ToolResult>().single()
        assertTrue(toolResult.isError)
        assertFalse(bAfterRan)
    }

    @Test
    fun tool_hook_exception_becomes_tool_failure() = runTest {
        val model = FakeLanguageModel(
            listOf(
                ModelEvent.ToolCallCompleted(ToolCall("c1", "noop", "{}")),
                ModelEvent.Completed(Usage.ZERO),
            ),
            listOf(ModelEvent.TextDelta("done"), ModelEvent.Completed(Usage.ZERO)),
        )
        val a = agent {
            name = "t"
            model { custom(model) }
            tools { tool<NoArgs>(name = "noop", description = "noop") { "ok" } }
            hook {
                onToolCall {
                    before { error("hook blew up") }
                }
            }
        }

        // Must not throw out of run — the hook failure travels the tool failure channel.
        val events = a.stream("go").toList()

        val toolResult = events.filterIsInstance<AgentEvent.ToolResult>().single()
        assertTrue(toolResult.isError)
    }

    @Test
    fun model_request_hook_exception_routes_through_error_policy() = runTest {
        val model = FakeLanguageModel(
            listOf(ModelEvent.TextDelta("never"), ModelEvent.Completed(Usage.ZERO)),
        )
        val a = agent {
            name = "t"
            model { custom(model) }
            hook {
                onModelCall {
                    before { error("request hook blew up") }
                }
            }
        }

        // A throwing model-request hook is mapped to an AgentError, not propagated raw.
        val result = a.run("go")

        assertTrue(result is AgentResult.Failed)
    }
}
