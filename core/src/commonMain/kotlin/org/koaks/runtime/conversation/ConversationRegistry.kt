package org.koaks.runtime.conversation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koaks.framework.loop.Agent
import org.koaks.framework.loop.AgentId
import org.koaks.framework.memory.MemoryProvider
import org.koaks.framework.memory.ThreadId
import org.koaks.framework.memory.ThreadMemory
import org.koaks.runtime.run.RunId

class ThreadMemoryPolicyConflictException(
    val threadId: ThreadId,
    val existingProviderId: String,
    val requestedProviderId: String,
) : IllegalStateException(
    "thread '${threadId.value}' is bound to memory provider '$existingProviderId', " +
        "but '$requestedProviderId' was requested",
)

/** Runtime-global conversation bindings plus submission-order FIFO admission. */
internal class ConversationRegistry(
    private val defaultMemoryProvider: MemoryProvider,
) {
    private val threads = MutableStateFlow<Map<ThreadId, ThreadBinding>>(emptyMap())

    /**
     * Reserves a top-level run's FIFO position synchronously in `RunManager.spawn`.
     * Consequently FIFO is defined by API submission order, not by whichever runtime
     * coroutine happens to reach a suspending mutex first.
     */
    fun reserve(threadId: ThreadId, runId: RunId, agent: Agent): RunReservation {
        val binding = bind(threadId, agent)
        return RunReservation(binding, binding.gate.enqueue(runId))
    }

    /**
     * Joins an already-active run without acquiring its FIFO gate. Child runs share
     * the root's Memory, while the root remains the sole queue owner.
     */
    fun joinActiveRun(threadId: ThreadId, runId: RunId, agent: Agent) {
        val binding = threads.value[threadId]
            ?: error("thread '${threadId.value}' has no active binding for inherited turn ${runId.value}")
        validateProvider(threadId, binding.provider, agent.memoryProvider)
        check(binding.gate.activeRun() == runId) {
            "turn '${runId.value}' is not active for thread '${threadId.value}'"
        }
        binding.participants.update { it + agent.id }
    }

    suspend fun memory(threadId: ThreadId): ThreadMemory =
        requireNotNull(threads.value[threadId]).memory()

    fun close() {
        threads.value.values.forEach { it.close() }
        threads.value = emptyMap()
    }

    fun snapshot(threadId: ThreadId): ConversationSnapshot? = threads.value[threadId]?.let { binding ->
        ConversationSnapshot(
            id = binding.threadId,
            memoryProviderId = binding.provider.id,
            participants = binding.participants.value,
            activeRun = binding.gate.activeRun(),
            queuedRuns = binding.gate.queuedRuns(),
        )
    }

    private fun bind(threadId: ThreadId, agent: Agent): ThreadBinding {
        while (true) {
            val current = threads.value
            val existing = current[threadId]
            if (existing != null) {
                validateProvider(threadId, existing.provider, agent.memoryProvider)
                existing.participants.update { it + agent.id }
                return existing
            }

            val created = ThreadBinding(
                threadId = threadId,
                provider = agent.memoryProvider ?: defaultMemoryProvider,
                initialParticipant = agent.id,
            )
            if (threads.compareAndSet(current, current + (threadId to created))) return created
        }
    }

    private fun validateProvider(
        threadId: ThreadId,
        existing: MemoryProvider,
        requested: MemoryProvider?,
    ) {
        if (requested != null && requested.id != existing.id) {
            throw ThreadMemoryPolicyConflictException(
                threadId = threadId,
                existingProviderId = existing.id.value,
                requestedProviderId = requested.id.value,
            )
        }
    }

    internal class ThreadBinding(
        val threadId: ThreadId,
        val provider: MemoryProvider,
        initialParticipant: AgentId,
        val participants: MutableStateFlow<Set<AgentId>> = MutableStateFlow(setOf(initialParticipant)),
        val gate: FifoRunGate = FifoRunGate(),
    ) {
        private val memoryMutex = Mutex()
        private val openedMemory = MutableStateFlow<ThreadMemory?>(null)

        suspend fun memory(): ThreadMemory = memoryMutex.withLock {
            openedMemory.value ?: provider.open(threadId).let {
                if (it === org.koaks.framework.memory.NoMemory) it else SerializedThreadMemory(it)
            }.also { openedMemory.value = it }
        }

        fun close() {
            openedMemory.value?.close()
            openedMemory.value = null
        }
    }

    internal class RunReservation(
        private val binding: ThreadBinding,
        private val reservation: FifoRunGate.Reservation,
    ) {
        suspend fun await(): ThreadLease {
            reservation.await()
            return ThreadLease(binding, reservation)
        }

        /** Safe before await, while queued, while active, and after normal release. */
        fun cancel() = reservation.finish()
    }

    internal class ThreadLease(
        private val binding: ThreadBinding,
        private val reservation: FifoRunGate.Reservation,
    ) {
        val threadId: ThreadId get() = binding.threadId
        suspend fun memory(): ThreadMemory = binding.memory()

        suspend fun release() {
            reservation.finish()
        }
    }
}

/** Lock-free CAS queue whose head is the active Turn. */
internal class FifoRunGate {
    internal data class Waiter(val runId: RunId, val ready: CompletableDeferred<Unit>)
    private data class GateState(val active: Waiter? = null, val queue: List<Waiter> = emptyList())

    private val state = MutableStateFlow(GateState())

    fun enqueue(runId: RunId): Reservation {
        val waiter = Waiter(runId, CompletableDeferred())
        while (true) {
            val current = state.value
            val immediate = current.active == null
            val next = if (immediate) {
                current.copy(active = waiter)
            } else {
                current.copy(queue = current.queue + waiter)
            }
            if (state.compareAndSet(current, next)) {
                if (immediate) waiter.ready.complete(Unit)
                return Reservation(waiter)
            }
        }
    }

    fun activeRun(): RunId? = state.value.active?.runId
    fun queuedRuns(): List<RunId> = state.value.queue.map { it.runId }

    inner class Reservation internal constructor(private val waiter: Waiter) {
        suspend fun await() {
            try {
                waiter.ready.await()
            } catch (cancelled: CancellationException) {
                finish()
                throw cancelled
            }
        }

        /** Removes this waiter or, if active, promotes exactly the next queued waiter. */
        fun finish() {
            while (true) {
                val current = state.value
                val nextWaiter: Waiter?
                val next = when {
                    current.active === waiter -> {
                        nextWaiter = current.queue.firstOrNull()
                        GateState(active = nextWaiter, queue = current.queue.drop(1))
                    }
                    current.queue.any { it === waiter } -> {
                        nextWaiter = null
                        current.copy(queue = current.queue.filterNot { it === waiter })
                    }
                    else -> return
                }
                if (state.compareAndSet(current, next)) {
                    nextWaiter?.ready?.complete(Unit)
                    return
                }
            }
        }
    }
}

/** Serializes storage operations in admission order even for custom Memory implementations. */
private class SerializedThreadMemory(private val delegate: ThreadMemory) : ThreadMemory {
    private val mutex = Mutex()
    override suspend fun append(item: org.koaks.framework.model.ModelItem) = mutex.withLock { delegate.append(item) }
    override suspend fun load(query: List<org.koaks.framework.model.ModelItem>) = mutex.withLock { delegate.load(query) }
    override fun close() = delegate.close()
}
