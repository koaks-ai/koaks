package org.koaks.memory.summarizing

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import org.koaks.framework.memory.ConversationTurn
import org.koaks.framework.memory.MemoryProvider
import org.koaks.framework.memory.MemoryProviderId
import org.koaks.framework.memory.MemoryView
import org.koaks.framework.memory.ThreadId
import org.koaks.framework.memory.ThreadMemory
import org.koaks.framework.memory.TurnRetention
import org.koaks.framework.model.LanguageModel
import org.koaks.framework.model.ModelItem
import org.koaks.framework.model.ModelRequest
import org.koaks.framework.model.ModelResponse
import org.koaks.framework.model.ReplayPolicy
import org.koaks.framework.model.TranscriptBasis
import org.koaks.framework.model.displayText
import org.koaks.framework.model.generate
import org.koaks.framework.model.isSystem
import org.koaks.framework.model.isUserTurnBoundary
import org.koaks.framework.model.newIdempotencyKey

data class SummaryCheckpoint(
    val basis: TranscriptBasis,
    val summary: ModelItem.Message,
    val sourceTurnId: String,
    val createdAtEpochMillis: Long,
)

interface SummaryStateStore {
    suspend fun load(threadId: ThreadId): SummaryCheckpoint?
    suspend fun save(threadId: ThreadId, checkpoint: SummaryCheckpoint)
    suspend fun delete(threadId: ThreadId)
}

class InMemorySummaryStateStore : SummaryStateStore {
    private val mutex = Mutex()
    private val states = mutableMapOf<ThreadId, SummaryCheckpoint>()

    override suspend fun load(threadId: ThreadId): SummaryCheckpoint? = mutex.withLock { states[threadId] }

    override suspend fun save(threadId: ThreadId, checkpoint: SummaryCheckpoint) {
        mutex.withLock { states[threadId] = checkpoint }
    }

    override suspend fun delete(threadId: ThreadId) {
        mutex.withLock { states.remove(threadId) }
    }
}

sealed interface CompactionEvent {
    val threadId: ThreadId
    val sourceTurnId: String

    data class Started(
        override val threadId: ThreadId,
        override val sourceTurnId: String,
        val basis: TranscriptBasis,
    ) : CompactionEvent

    data class Completed(
        override val threadId: ThreadId,
        override val sourceTurnId: String,
        val checkpoint: SummaryCheckpoint,
    ) : CompactionEvent

    data class Failed(
        override val threadId: ThreadId,
        override val sourceTurnId: String,
        val message: String,
    ) : CompactionEvent
}

fun interface CompactionObserver {
    fun onCompaction(event: CompactionEvent)
}

/**
 * A lossless summarizing decorator. The delegate remains the source of truth for raw turns;
 * only the model-facing projection and its checkpoint are compacted.
 *
 * The delegate must return its complete append-only transcript from [ThreadMemory.load].
 */
class SummarizingMemoryProvider(
    override val id: MemoryProviderId,
    private val delegate: MemoryProvider,
    private val stateStore: SummaryStateStore,
    private val model: LanguageModel,
    private val maxTokens: Int,
    private val keepRecentTurns: Int = 2,
    private val retention: TurnRetention = TurnRetention.Interrupted,
    private val observer: CompactionObserver = CompactionObserver {},
) : MemoryProvider {
    private class ThreadCoordinator {
        val mutex = Mutex()
        val committedTurnIds = mutableSetOf<String>()
    }

    private val coordinatorsMutex = Mutex()
    private val coordinators = mutableMapOf<ThreadId, ThreadCoordinator>()

    init {
        require(maxTokens > 0) { "maxTokens must be positive" }
        require(keepRecentTurns >= 1) { "keepRecentTurns must be at least one" }
    }

    override suspend fun open(thread: ThreadId): ThreadMemory {
        val coordinator = coordinatorsMutex.withLock {
            coordinators.getOrPut(thread) { ThreadCoordinator() }
        }
        return CompactingThreadMemory(
            thread,
            delegate.open(thread),
            stateStore,
            model,
            maxTokens,
            keepRecentTurns,
            retention,
            observer,
            coordinator.mutex,
            coordinator.committedTurnIds,
        )
    }
}

/** Append-only process-local delegate used when no durable store is configured. */
class InMemoryAppendMemoryProvider(
    override val id: MemoryProviderId = MemoryProviderId("summarizing-raw-memory"),
    private val retention: TurnRetention = TurnRetention.Interrupted,
) : MemoryProvider {
    private val mutex = Mutex()
    private val threads = mutableMapOf<ThreadId, MutableList<ConversationTurn>>()

    override suspend fun open(thread: ThreadId): ThreadMemory = object : ThreadMemory {
        override val retention: TurnRetention = this@InMemoryAppendMemoryProvider.retention

        override suspend fun load(query: List<ModelItem>): MemoryView = mutex.withLock {
            val turns = threads[thread].orEmpty()
            MemoryView(
                transcript = turns.flatMap { it.items },
                checkpoint = turns.lastOrNull()?.checkpoint,
            )
        }

        override suspend fun commit(turn: ConversationTurn) {
            mutex.withLock {
                val turns = threads.getOrPut(thread) { mutableListOf() }
                if (turns.none { it.id == turn.id }) turns.add(turn)
            }
        }
    }
}

private class CompactingThreadMemory(
    private val threadId: ThreadId,
    private val delegate: ThreadMemory,
    private val stateStore: SummaryStateStore,
    private val model: LanguageModel,
    private val maxTokens: Int,
    private val keepRecentTurns: Int,
    override val retention: TurnRetention,
    private val observer: CompactionObserver,
    private val mutex: Mutex,
    private val committedTurnIds: MutableSet<String>,
) : ThreadMemory {
    override suspend fun load(query: List<ModelItem>): MemoryView = mutex.withLock {
        project(delegate.load(query))
    }

    override suspend fun commit(turn: ConversationTurn) = mutex.withLock {
        if (turn.id in committedTurnIds) return@withLock
        delegate.commit(turn)
        committedTurnIds += turn.id
        if (turn.usage.promptTokens <= maxTokens) return@withLock

        val raw = delegate.load(emptyList())
        val system = raw.transcript.takeWhile { it.isSystem() }
        val turns = groupIntoTurns(raw.transcript.drop(system.size))
        if (turns.size <= keepRecentTurns) return@withLock

        val prefix = system + turns.dropLast(keepRecentTurns).flatten()
        val basis = TranscriptBasis.of(prefix)
        notify(CompactionEvent.Started(threadId, turn.id, basis))
        try {
            val summary = summarize(prefix.drop(system.size))
            val checkpoint = SummaryCheckpoint(
                basis = basis,
                summary = ModelItem.system("Summary of earlier conversation:\n$summary"),
                sourceTurnId = turn.id,
                createdAtEpochMillis = Clock.System.now().toEpochMilliseconds(),
            )
            stateStore.save(threadId, checkpoint)
            notify(CompactionEvent.Completed(threadId, turn.id, checkpoint))
        } catch (failure: Throwable) {
            notify(CompactionEvent.Failed(threadId, turn.id, failure.message ?: "compaction failed"))
            throw failure
        }
    }

    override fun close() = delegate.close()

    private suspend fun project(raw: MemoryView): MemoryView {
        val state = stateStore.load(threadId) ?: return raw
        val count = state.basis.itemCount
        if (raw.transcript.size < count) {
            stateStore.delete(threadId)
            return raw
        }
        val prefix = raw.transcript.take(count)
        if (TranscriptBasis.of(prefix) != state.basis) {
            stateStore.delete(threadId)
            return raw
        }
        val system = prefix.takeWhile { it.isSystem() }
        val required = prefix.drop(system.size)
            .filterIsInstance<ModelItem.ProviderItem>()
            .filter { it.replay == ReplayPolicy.Required }
        return MemoryView(
            transcript = system + state.summary + required + raw.transcript.drop(count),
            checkpoint = null,
        )
    }

    private suspend fun summarize(items: List<ModelItem>): String {
        val request = ModelRequest(
            instructions = "Summarize the following conversation concisely, preserving facts, decisions, and open questions.",
            items = listOf(ModelItem.user(items.joinToString("\n") { it.displayText() })),
            idempotencyKey = newIdempotencyKey(),
        )
        val response = model.generate(request)
        check(response is ModelResponse.Completed) { "summary model did not complete: $response" }
        return response.output.filterIsInstance<ModelItem.Message>().joinToString("") { it.text }
            .trim()
            .ifBlank { error("summary model returned empty output") }
    }

    private fun notify(event: CompactionEvent) {
        runCatching { observer.onCompaction(event) }
    }

    private fun groupIntoTurns(items: List<ModelItem>): List<List<ModelItem>> {
        val turns = mutableListOf<MutableList<ModelItem>>()
        for (item in items) {
            if (item.isUserTurnBoundary() || turns.isEmpty()) turns += mutableListOf(item)
            else turns.last() += item
        }
        return turns
    }
}
