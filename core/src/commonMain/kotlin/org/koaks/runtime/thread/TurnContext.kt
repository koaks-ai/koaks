package org.koaks.runtime.thread

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.koaks.framework.memory.ThreadId
import org.koaks.framework.memory.TurnCommitBuffer
import org.koaks.framework.model.Message
import org.koaks.runtime.acb.TurnId

/** Shared state for every parent/child Run that participates in one atomic Thread Turn. */
internal class TurnContext(
    val threadId: ThreadId,
    val turnId: TurnId,
    userMessage: Message,
) {
    val commitBuffer: TurnCommitBuffer = TurnCommitBuffer(userMessage)

    private val history = CompletableDeferred<List<Message>>()
    private val sideEffectOccurred = MutableStateFlow(false)

    fun publishHistory(snapshot: List<Message>) {
        check(history.complete(snapshot.toList())) { "history was already published for $turnId" }
    }

    suspend fun historySnapshot(): List<Message> = history.await()

    fun markSideEffect() {
        sideEffectOccurred.value = true
    }

    val hasSideEffects: Boolean get() = sideEffectOccurred.value
}
