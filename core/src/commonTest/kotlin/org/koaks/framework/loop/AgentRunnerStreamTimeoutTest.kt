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
import org.koaks.framework.transport.StreamIdleTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AgentRunnerStreamTimeoutTest {
    @Test
    fun surfacesMidStreamIdleTimeoutAsFailedEvent() = runTest {
        val model = object : LanguageModel {
            override val capabilities = ModelCapabilities()

            override fun generate(request: ChatRequest): Flow<ModelEvent> = flow {
                emit(ModelEvent.TextDelta("partial"))
                throw StreamIdleTimeoutException(idleTimeoutMs = 60_000)
            }
        }
        val agent = agent {
            id = "agent-70"
            model { custom(model) }
        }

        val events = agent.stream("hello").toList()

        assertTrue(events.any { it is AgentEvent.TextDelta && it.text == "partial" })
        val failure = assertIs<AgentEvent.Failed>(events.last())
        val timeout = assertIs<AgentError.Timeout>(failure.error)
        assertEquals("model response stream idle", timeout.stage)
        assertEquals(60_000, timeout.elapsedMs)
    }
}
