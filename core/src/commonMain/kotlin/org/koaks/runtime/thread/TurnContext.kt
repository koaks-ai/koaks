package org.koaks.runtime.thread

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.koaks.framework.loop.TurnBuilder
import org.koaks.framework.memory.ThreadId
import org.koaks.framework.model.ModelItem
import org.koaks.runtime.acb.TurnId

internal class TurnContext(
    val threadId: ThreadId,
    val turnId: TurnId,
    seed: List<ModelItem>,
) {
    val turnBuilder: TurnBuilder = TurnBuilder(turnId.value.toString(), seed)

    private val history = CompletableDeferred<List<ModelItem>>()
    private val sideEffectOccurred = MutableStateFlow(false)
    var checkpoint: org.koaks.framework.model.ProviderCheckpoint? = null

    fun publishHistory(snapshot: List<ModelItem>) {
        check(history.complete(snapshot.toList())) { "history was already published for $turnId" }
    }

    suspend fun historySnapshot(): List<ModelItem> = history.await()

    fun markSideEffect() {
        sideEffectOccurred.value = true
    }

    val hasSideEffects: Boolean get() = sideEffectOccurred.value
}
