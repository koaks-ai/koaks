package org.koaks.runtime.resource

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** How a shared resource is being accessed. Recorded for observability; see note below. */
enum class AccessMode { READ, WRITE }

/**
 * Mediates access to **shared, contended** resources across concurrent agent instances —
 * the same file, the same ThreadMemory backend, the same external system. It hands out a
 * lock-guarded critical section keyed by a resource id; it is deliberately NOT a gate for
 * *all* tool IO (private, uncontended IO stays direct — routing everything through here
 * would needlessly serialize independent work and reintroduce a global chokepoint).
 *
 * Locking model (Phase 2): one exclusive [Mutex] per resource id, so both [AccessMode.READ]
 * and [AccessMode.WRITE] currently serialize on the same id. This is correct (it never
 * over-shares) and matches the plan's "Mutex first, RW lock later". A shared-readers /
 * exclusive-writer refinement can replace the per-id lock without changing this API.
 */
class ResourceRegistry {
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
