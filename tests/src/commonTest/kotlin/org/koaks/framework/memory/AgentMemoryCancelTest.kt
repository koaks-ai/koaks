package org.koaks.framework.memory

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.koaks.framework.loop.FakeLanguageModel
import org.koaks.framework.loop.agent
import org.koaks.framework.loop.done
import org.koaks.framework.model.ModelEvent
import org.koaks.framework.model.Usage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentMemoryCancelTest {

    /**
     * Cancelling mid-stream still writes the user message and any partial assistant text.
     */
    @Test
    fun cancelled_run_keeps_user_and_partial_assistant() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val model = FakeLanguageModel(
            ArrayDeque(listOf(listOf(ModelEvent.TextDelta("partial"), done(Usage.ZERO)))),
            beforeEmit = { event ->
                if (event !is ModelEvent.TextDelta) {
                    entered.complete(Unit)
                    release.await()
                }
            },
        )
        val mem = WindowMemory(50)
        val a = agent {
            id = "agent-29"
            name = "mem-cancel"
            model { custom(model) }
            memory { custom("test-memory") { mem } }
            terminateAfter(maxSteps = 5)
        }

        val job = launch { a.stream("q1", thread = "u").collect { } }
        entered.await()
        job.cancel()
        job.join()

        val stored = mem.stored().filterIsInstance<org.koaks.framework.model.ModelItem.Message>()
        assertEquals(listOf("q1"), stored.filter { it.role == org.koaks.framework.model.Role.USER }.map { it.text })
        assertTrue(stored.any { it.role == org.koaks.framework.model.Role.ASSISTANT && it.text.contains("partial") })
    }
}
