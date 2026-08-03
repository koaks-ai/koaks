package org.koaks.java.internal

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val threadSequence = AtomicLong()

internal fun nextVirtualThreadName(prefix: String): String =
    "$prefix-${threadSequence.incrementAndGet()}"

internal fun <T> virtualFuture(
    prefix: String,
    onCancel: () -> Unit = {},
    block: suspend () -> T,
): CompletableFuture<T> {
    val future = InterruptibleFuture<T>(onCancel)
    val thread = Thread.ofVirtual()
        .name(nextVirtualThreadName(prefix))
        .unstarted {
            try {
                val value = runBlocking { block() }
                future.complete(value)
            } catch (failure: Throwable) {
                if (!future.isCancelled) future.completeExceptionally(unwrapCompletionFailure(failure))
            }
        }
    future.bind(thread)
    thread.start()
    return future
}

internal suspend fun <T> runOnVirtualThread(prefix: String, block: () -> T): T =
    suspendCancellableCoroutine { continuation ->
        val thread = Thread.ofVirtual()
            .name(nextVirtualThreadName(prefix))
            .unstarted {
                try {
                    val value = block()
                    if (continuation.isActive) continuation.resume(value)
                } catch (failure: Throwable) {
                    if (continuation.isActive) continuation.resumeWithException(failure)
                }
            }
        continuation.invokeOnCancellation { thread.interrupt() }
        thread.start()
    }

internal suspend fun <T> CompletionStage<T>.awaitStage(): T =
    suspendCancellableCoroutine { continuation ->
        val future = toCompletableFuture()
        whenComplete { value, failure ->
            if (!continuation.isActive) return@whenComplete
            if (failure == null) continuation.resume(value)
            else continuation.resumeWithException(unwrapCompletionFailure(failure))
        }
        continuation.invokeOnCancellation { future.cancel(true) }
    }

internal fun unwrapCompletionFailure(failure: Throwable): Throwable = when (failure) {
    is CompletionException, is ExecutionException -> failure.cause ?: failure
    else -> failure
}

private class InterruptibleFuture<T>(
    private val onCancel: () -> Unit,
) : CompletableFuture<T>() {
    @Volatile
    private var bridgeThread: Thread? = null

    fun bind(thread: Thread) {
        bridgeThread = thread
        if (isCancelled) thread.interrupt()
    }

    override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
        val cancelled = super.cancel(mayInterruptIfRunning)
        if (cancelled) {
            onCancel()
            if (mayInterruptIfRunning) bridgeThread?.interrupt()
        }
        return cancelled
    }
}
