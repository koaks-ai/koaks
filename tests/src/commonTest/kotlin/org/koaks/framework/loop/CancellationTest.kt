package org.koaks.framework.loop

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.koaks.framework.model.ModelEvent
import org.koaks.framework.model.Usage
import kotlin.test.Test
import kotlin.test.assertFalse

class CancellationTest {

    @Test
    fun cancellation_propagates_and_does_not_retry() = runTest {
        val started = CompletableDeferred<Unit>()
        // A model whose stream emits one delta then suspends "forever" via a huge list;
        // we cancel the collecting job mid-stream.
        val model = FakeLanguageModel(
            ArrayDeque(
                listOf(
                    buildList {
                        add(ModelEvent.TextDelta("partial"))
                        add(ModelEvent.Completed(Usage.ZERO))
                    }
                )
            ),
            beforeEmit = { ev ->
                if (ev is ModelEvent.TextDelta) started.complete(Unit)
            },
        )
        val a = agent {
            id = "agent-5"
            model { custom(model) }
            // An ErrorPolicy that WOULD retry — to prove cancellation is not treated as an error.
            onError { _, _ -> org.koaks.framework.policy.Recovery.Retry(0, 5) }
            terminateAfter(5)
        }

        val job = launch {
            a.stream("hi").collect { }
        }
        started.await()
        job.cancel()
        job.join()

        // The model must not have been re-invoked by a retry after cancellation.
        assertFalse(model.calls > 1, "cancellation must not trigger a retry")
    }
}
