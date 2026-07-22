package org.koaks.framework.memory

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.koaks.framework.loop.FakeLanguageModel
import org.koaks.framework.loop.agent
import org.koaks.framework.model.ModelEvent
import org.koaks.framework.model.Usage
import kotlin.test.Test
import kotlin.test.assertEquals

class AgentMemoryCancelTest {

    /**
     * A memory-backed turn cancelled mid-flight (e.g. a runtime quota preemption or the caller
     * stopping collection) must leave persisted history untouched — the commit-or-warn step now
     * runs in a NonCancellable `finally`, so a discarded turn is resolved rather than skipped.
     */
    @Test
    fun cancelled_memory_turn_is_not_committed() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val model = FakeLanguageModel(
            ArrayDeque(listOf(listOf(ModelEvent.TextDelta("partial"), ModelEvent.Completed(Usage.ZERO)))),
            beforeEmit = { entered.complete(Unit); release.await() }, // blocks before any terminal
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

        assertEquals(0, mem.load(org.koaks.framework.model.Message.user("")).size, "a cancelled turn must leave history untouched")
    }
}
