package org.koaks.runtime.sched

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Cooperative admission scheduler: caps concurrently RUNNING instances at
 * [maxConcurrency] and, when saturated, admits waiters by **priority** (higher first),
 * breaking ties FIFO. This is the "CPU scheduler" of the kernel — it decides which
 * ready instance gets to run next, not how to preempt one (there is no preemption).
 *
 * FIFO scheduling is the special case where all priorities are equal.
 */
internal class Scheduler(private val maxConcurrency: Int) {

    private val unbounded = maxConcurrency == Int.MAX_VALUE
    private val mutex = Mutex()
    private var running = 0
    private var seq = 0L

    private class Waiter(val priority: Int, val seq: Long) {
        val gate = CompletableDeferred<Unit>()
    }

    private val waiters = ArrayList<Waiter>()

    /** Acquires a slot (respecting priority), runs [block], then releases the slot. */
    suspend fun <T> withSlot(priority: Int, block: suspend () -> T): T {
        acquire(priority)
        try {
            return block()
        } finally {
            release()
        }
    }

    private suspend fun acquire(priority: Int) {
        if (unbounded) return
        val waiter = mutex.withLock {
            if (running < maxConcurrency) {
                running++
                return
            }
            Waiter(priority, seq++).also { waiters += it }
        }
        try {
            waiter.gate.await()
        } catch (c: CancellationException) {
            mutex.withLock {
                val stillWaiting = waiters.remove(waiter)
                // If the slot was already handed to us just before cancellation, pass it on.
                if (!stillWaiting) handOffOrDecrement()
            }
            throw c
        }
    }

    private suspend fun release() {
        if (unbounded) return
        mutex.withLock { handOffOrDecrement() }
    }

    /** Must be called under [mutex]. Hands the freed slot to the best waiter, or frees it. */
    private fun handOffOrDecrement() {
        val next = waiters.minWithOrNull(
            compareByDescending<Waiter> { it.priority }.thenBy { it.seq },
        )
        if (next != null) {
            waiters.remove(next)
            next.gate.complete(Unit)
        } else {
            running--
        }
    }
}
