package org.koaks.runtime.internal

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koaks.runtime.coordination.AccessMode

/**
 * Mediates access to **shared, contended** resources across concurrent agent instances —
 * the same file, the same ThreadMemory backend, the same external system. It hands out a
 * lock-guarded critical section keyed by a resource id; it is deliberately NOT a gate for
 * *all* tool IO (private, uncontended IO stays direct — routing everything through here
 * would needlessly serialize independent work and reintroduce a global chokepoint).
 *
 * One exclusive [Mutex] per resource id; both [AccessMode.READ] and [AccessMode.WRITE]
 * currently serialize on the same id.
 */
internal class SharedResourceCoordinator {
    private val tableMutex = Mutex()
    private val locks = HashMap<String, Mutex>()

    private suspend fun mutexFor(id: String): Mutex =
        tableMutex.withLock { locks.getOrPut(id) { Mutex() } }

    /**
     * Runs [block] holding the lock for resource [id]. [mode] is reserved for the future
     * shared-readers / exclusive-writer refinement (both modes currently lock exclusively).
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun <T> withResource(
        id: String,
        mode: AccessMode = AccessMode.WRITE,
        block: suspend () -> T,
    ): T {
        val lock = mutexFor(id)
        return lock.withLock { block() }
    }

    /** The set of resource ids the registry has created a lock for (best-effort view). */
    val trackedResources: Set<String>
        get() = locks.keys.toSet()
}
