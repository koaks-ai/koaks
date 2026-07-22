package org.koaks.framework.loop

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.koaks.framework.model.AgentError
import org.koaks.framework.model.ChatRequest
import org.koaks.framework.model.LanguageModel
import org.koaks.framework.model.ModelCapabilities
import org.koaks.framework.model.ModelEvent
import org.koaks.framework.model.Usage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Exercises `model { custom(a).fallback(custom(b)) }` through a full agent run —
 * the only public surface of the fallback feature.
 */
class FallbackModelTest {

    /** Emits [error] as a Failed event before producing any output. */
    private fun failsImmediately(error: AgentError.ModelError) = object : LanguageModel {
        override val capabilities = ModelCapabilities()
        override fun generate(request: ChatRequest): Flow<ModelEvent> = flow {
            emit(ModelEvent.Failed(error))
        }
    }

    /** Throws before producing any output. */
    private fun throwsImmediately(t: Throwable) = object : LanguageModel {
        override val capabilities = ModelCapabilities()
        override fun generate(request: ChatRequest): Flow<ModelEvent> = flow { throw t }
    }

    private fun succeeds(text: String) = FakeLanguageModel(
        listOf(ModelEvent.TextDelta(text), ModelEvent.Completed(Usage.ZERO)),
    )

    @Test
    fun falls_back_when_primary_fails_before_output() = runTest {
        val a = agent {
            id = "agent-6"
            name = "t"
            model {
                custom(
                    failsImmediately(
                        AgentError.ModelError(
                            "down", true
                        )
                    )
                ).fallback(custom(succeeds("from-secondary")))
            }
        }
        val result = a.run("hi")
        assertEquals("from-secondary", result.text)
        assertTrue(result.isSuccess)
    }

    @Test
    fun falls_back_when_primary_throws_before_output() = runTest {
        val a = agent {
            id = "agent-7"
            name = "t"
            model {
                custom(
                    throwsImmediately(
                        RuntimeException("connect refused")
                    )
                ).fallback(custom(succeeds("recovered")))
            }
        }
        assertEquals("recovered", a.run("hi").text)
    }

    @Test
    fun does_not_fall_back_once_output_has_started() = runTest {
        val secondaryUsed = booleanArrayOf(false)
        val primary = object : LanguageModel {
            override val capabilities = ModelCapabilities()
            override fun generate(request: ChatRequest): Flow<ModelEvent> = flow {
                emit(ModelEvent.TextDelta("partial"))
                emit(ModelEvent.Failed(AgentError.ModelError("mid-stream", retriable = false)))
            }
        }
        val secondary = object : LanguageModel {
            override val capabilities = ModelCapabilities()
            override fun generate(request: ChatRequest): Flow<ModelEvent> = flow {
                secondaryUsed[0] = true
                emit(ModelEvent.TextDelta("should-not-appear"))
            }
        }
        val a = agent {
            id = "agent-8"
            name = "t"
            model { custom(primary).fallback(custom(secondary)) }
        }
        val events = a.stream("hi").toList()

        assertFalse(secondaryUsed[0], "must not fall back after a token was emitted")
        val text = events.filterIsInstance<AgentEvent.TextDelta>().joinToString("") { it.text }
        assertTrue(text.contains("partial"))
        assertTrue(events.any { it is AgentEvent.Failed }, "committed failure must propagate")
    }

    @Test
    fun surfaces_failure_when_all_models_fail() = runTest {
        val a = agent {
            id = "agent-9"
            name = "t"
            model {
                custom(failsImmediately(AgentError.ModelError("first-down", true))).fallback(
                    custom(
                        failsImmediately(
                            AgentError.ModelError("second-down", true)
                        )
                    )
                )
            }
        }
        val events = a.stream("hi").toList()
        assertTrue(events.any { it is AgentEvent.Failed }, "exhausting all models must surface a failure")
    }

    @Test
    fun three_way_chain_uses_first_healthy() = runTest {
        val a = agent {
            id = "agent-10"
            name = "t"
            model {
                custom(
                    failsImmediately(
                        AgentError.ModelError(
                            "a",
                            true
                        )
                    )
                ).fallback(custom(failsImmediately(AgentError.ModelError("b", true)))).fallback(custom(succeeds("c")))
            }
        }
        assertEquals("c", a.run("hi").text)
    }
}
