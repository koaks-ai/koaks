package org.koaks.java

import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.runBlocking
import org.koaks.framework.loop.AgentResult
import org.koaks.java.internal.virtualFuture
import org.koaks.runtime.run.RunHandle as CoreRunHandle
import org.koaks.runtime.run.RunState

/** Java-friendly control handle for a spawned Agent execution. */
class RunHandle private constructor(
    private val delegate: CoreRunHandle,
) {
    val runId: Long get() = delegate.runId.value
    val agentId: String get() = delegate.agentId.value
    val threadId: String? get() = delegate.threadId?.value
    val state: RunState get() = delegate.state
    val isActive: Boolean get() = delegate.isActive

    @Volatile
    private var future: CompletableFuture<AgentResult>? = null

    @Throws(InterruptedException::class)
    fun await(): AgentResult = runBlocking { delegate.await() }

    fun resultAsync(): CompletableFuture<AgentResult> {
        future?.let { return it }
        return synchronized(this) {
            future ?: virtualFuture(
                prefix = "koaks-run-$runId",
                onCancel = { delegate.cancel("Java CompletableFuture cancelled") },
            ) { delegate.await() }.also { future = it }
        }
    }

    fun cancel() = delegate.cancel("cancelled by Java caller")

    fun cancel(reason: String?) = delegate.cancel(reason)

    fun pause() = delegate.pause()

    fun resume() = delegate.resume()

    fun unwrap(): CoreRunHandle = delegate

    companion object {
        internal fun from(delegate: CoreRunHandle): RunHandle = RunHandle(delegate)
    }
}
