package org.koaks.runtime.thread

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koaks.framework.loop.Agent
import org.koaks.framework.loop.AgentId
import org.koaks.framework.memory.MemoryProvider
import org.koaks.framework.memory.MemoryProviderId
import org.koaks.framework.memory.ThreadId
import org.koaks.framework.memory.ThreadMemory
import org.koaks.runtime.acb.TurnId

class ThreadMemoryPolicyConflictException(
    val threadId: ThreadId,
    val existingProviderId: String,
    val requestedProviderId: String,
) : IllegalStateException(
    "thread '${threadId.value}' is bound to memory provider '$existingProviderId', " +
        "but '$requestedProviderId' was requested",
)

/** Runtime-global Thread bindings plus submission-order FIFO admission. */
internal class ThreadRegistry(
    private val defaultMemoryProvider: MemoryProvider,
) {
    private val threads = MutableStateFlow<Map<ThreadId, ThreadBinding>>(emptyMap())

    /**
     * Reserves a top-level Turn's FIFO position synchronously in `spawnInternal`.
     * Consequently FIFO is defined by API submission order, not by whichever runtime
     * coroutine happens to reach a suspending mutex first.
     */
    fun reserve(threadId: ThreadId, turnId: TurnId, agent: Agent): TurnReservation {
        val binding = bind(threadId, agent)
        return TurnReservation(binding, binding.gate.enqueue(turnId))
    }

    /**
     * Joins an already-active Turn without acquiring its FIFO gate. Child runs share
     * their root Turn's provider/history, while the root remains the sole queue and
     * atomic-commit owner.
     */
    fun joinActiveTurn(threadId: ThreadId, turnId: TurnId, agent: Agent) {
        val binding = threads.value[threadId]
            ?: error("thread '${threadId.value}' has no active binding for inherited turn ${turnId.value}")
        validateProvider(threadId, binding.provider, agent.memoryProvider)
        check(binding.gate.activeTurn() == turnId) {
            "turn '${turnId.value}' is not active for thread '${threadId.value}'"
        }
        binding.participants.update { it + agent.id }
    }

    fun close() {
        threads.value.values.forEach { it.close() }
        threads.value = emptyMap()
    }

    fun snapshot(threadId: ThreadId): ThreadSnapshot? = threads.value[threadId]?.let { binding ->
        ThreadSnapshot(
            id = binding.threadId,
            memoryProviderId = binding.provider.id,
            participants = binding.participants.value,
            activeTurn = binding.gate.activeTurn(),
            queuedTurns = binding.gate.queuedTurns(),
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
        val gate: FifoTurnGate = FifoTurnGate(),
    ) {
        private val memoryMutex = Mutex()
        private val openedMemory = MutableStateFlow<ThreadMemory?>(null)

        suspend fun memory(): ThreadMemory = memoryMutex.withLock {
            openedMemory.value ?: provider.open(threadId).also { openedMemory.value = it }
        }

        fun close() {
            openedMemory.value?.close()
            openedMemory.value = null
        }
    }

    internal class TurnReservation(
        private val binding: ThreadBinding,
        private val reservation: FifoTurnGate.Reservation,
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
        private val reservation: FifoTurnGate.Reservation,
    ) {
        val threadId: ThreadId get() = binding.threadId
        suspend fun memory(): ThreadMemory = binding.memory()

        suspend fun release() {
            reservation.finish()
        }
    }
}

data class ThreadSnapshot(
    val id: ThreadId,
    val memoryProviderId: MemoryProviderId,
    val participants: Set<AgentId>,
    val activeTurn: TurnId?,
    val queuedTurns: List<TurnId>,
)

/** Lock-free CAS queue whose head is the active Turn. */
internal class FifoTurnGate {
    internal data class Waiter(val turnId: TurnId, val ready: CompletableDeferred<Unit>)
    private data class GateState(val active: Waiter? = null, val queue: List<Waiter> = emptyList())

    private val state = MutableStateFlow(GateState())

    fun enqueue(turnId: TurnId): Reservation {
        val waiter = Waiter(turnId, CompletableDeferred())
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

    fun activeTurn(): TurnId? = state.value.active?.turnId
    fun queuedTurns(): List<TurnId> = state.value.queue.map { it.turnId }

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
