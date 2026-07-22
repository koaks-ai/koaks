package org.koaks.runtime.sched

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A held admission slot, owned by one instance. It can be [park]ed — handed back to the
 * scheduler while the owner blocks on a child, an IPC reply, or a resource lock — and
 * later [unpark]ed to re-admit the owner. A parked owner does not count against the
 * concurrency cap, which is what makes "waiting" mean "off the CPU" and breaks the
 * parent/child slot deadlock: a parent awaiting a child releases its slot so the child
 * (and grandchildren) can be admitted, even at `maxConcurrency = 1`.
 *
 * All state changes are linearized under a per-lease [Mutex] so concurrent branches of a
 * single instance (parallel tool calls) can drive it safely, and permit-returning work
 * runs [NonCancellable] so a cancel can never strand a permit.
 */
internal interface SlotLease {
    /** Releases the permit while the owner blocks. No-op if not currently held. */
    suspend fun park()

    /**
     * Re-acquires the permit after the wait. No-op if already held. Suspends until a
     * permit is free; if the owner is cancelled while queued, the permit is not acquired,
     * the lease stays parked, and the cancellation propagates — keeping acquire/release
     * balanced.
     */
    suspend fun unpark()

    /** Returns the permit for good (idempotent, cancellation-immune). Called from `finally`. */
    suspend fun close()
}

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

    private class Waiter(val priority: Int, val seq: Long, val resume: Boolean) {
        val gate = CompletableDeferred<Unit>()
    }

    private val waiters = ArrayList<Waiter>()

    private enum class LeaseState { HELD, PARKED, CLOSED }

    private inner class Lease(private val priority: Int) : SlotLease {
        private val leaseMutex = Mutex()
        private var state = LeaseState.HELD

        override suspend fun park() {
            leaseMutex.withLock {
                if (state != LeaseState.HELD) return
                // Return the permit uninterruptibly: a park must never be cut short and
                // leave the permit both "released to us" and never handed on.
                withContext(NonCancellable) { release() }
                state = LeaseState.PARKED
            }
        }

        override suspend fun unpark() {
            leaseMutex.withLock {
                if (state != LeaseState.PARKED) return
                // May suspend (and may be cancelled) while queued. On cancellation the
                // permit is not taken and state stays PARKED, so `close` stays a no-op and
                // the count balances.
                acquire(priority, resume = true)
                state = LeaseState.HELD
            }
        }

        override suspend fun close() {
            withContext(NonCancellable) {
                leaseMutex.withLock {
                    if (state == LeaseState.HELD) release()
                    state = LeaseState.CLOSED
                }
            }
        }
    }

    /** Acquires a permit, runs [block] with a parkable [SlotLease], then returns the permit. */
    suspend fun <T> withSlot(priority: Int, block: suspend (SlotLease) -> T): T {
        acquire(priority, resume = false)
        val lease = Lease(priority)
        try {
            return block(lease)
        } finally {
            lease.close()
        }
    }

    private suspend fun acquire(priority: Int, resume: Boolean) {
        if (unbounded) return
        val waiter = mutex.withLock {
            if (running < maxConcurrency) {
                running++
                return
            }
            Waiter(priority, seq++, resume).also { waiters += it }
        }
        try {
            waiter.gate.await()
        } catch (c: CancellationException) {
            // The coroutine is already cancelled, so cleanup must not attempt a cancellable
            // mutex acquisition in that context. Otherwise an abandoned waiter can remain in
            // the queue and later consume a permit that nobody receives.
            withContext(NonCancellable) {
                mutex.withLock {
                    val stillWaiting = waiters.remove(waiter)
                    // If the slot was already handed to us just before cancellation, pass it on.
                    if (!stillWaiting) handOffOrDecrement()
                }
            }
            throw c
        }
    }

    private suspend fun release() {
        if (unbounded) return
        mutex.withLock { handOffOrDecrement() }
    }

    /**
     * Must be called under [mutex]. Hands the freed slot to the best waiter, or frees it.
     * Ordering honors the public **priority** contract first, then prefers a resuming
     * waiter (a parked instance re-acquiring after a wait) over an equal-priority cold
     * spawn so a nearly-finished instance is not starved, then FIFO.
     */
    private fun handOffOrDecrement() {
        val next = waiters.minWithOrNull(
            compareByDescending<Waiter> { it.priority }
                .thenByDescending { it.resume }
                .thenBy { it.seq },
        )
        if (next != null) {
            waiters.remove(next)
            next.gate.complete(Unit)
        } else {
            running--
        }
    }
}
