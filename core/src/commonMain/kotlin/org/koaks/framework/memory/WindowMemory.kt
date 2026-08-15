package org.koaks.framework.memory

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koaks.framework.model.AgentError
import org.koaks.framework.model.AgentFrameworkException
import org.koaks.framework.model.ModelItem
import org.koaks.framework.model.ReplayPolicy
import org.koaks.framework.model.isSystem
import org.koaks.framework.model.isUserTurnBoundary

/**
 * Sliding-window memory (core default). Persists every committed turn faithfully;
 * trimming happens only on [load].
 *
 * **Turn-atomic trimming**: the window unit is a complete user-bounded turn, NOT a
 * single item. Dropping whole turns never orphans a tool result from its call.
 * Leading system items are always preserved.
 */
class WindowMemory(
    private val maxMessages: Int,
    override val retention: TurnRetention = TurnRetention.Interrupted,
) : ThreadMemory {

    private val mutex = Mutex()
    private val items = mutableListOf<ModelItem>()
    private var latestCheckpoint: org.koaks.framework.model.ProviderCheckpoint? = null

    override suspend fun load(query: List<ModelItem>): MemoryView = mutex.withLock {
        MemoryView(
            transcript = dropTurnsToFit(items, maxMessages),
            checkpoint = latestCheckpoint,
        )
    }

    override suspend fun commit(turn: ConversationTurn): Unit = mutex.withLock {
        if (!shouldRetain(turn, retention, sideEffects = turnHasSideEffects(turn))) return
        items.addAll(turn.items)
        latestCheckpoint = turn.checkpoint
    }

    internal companion object {
        fun dropTurnsToFit(items: List<ModelItem>, max: Int): List<ModelItem> {
            if (items.size <= max) return items

            val system = items.takeWhile { it.isSystem() }
            val rest = items.drop(system.size)
            val turns = groupIntoTurns(rest)

            val budget = (max - system.size).coerceAtLeast(0)
            val kept = ArrayDeque<List<ModelItem>>()
            var count = 0
            for (turn in turns.asReversed()) {
                if (count + turn.size > budget && kept.isNotEmpty()) break
                kept.addFirst(turn)
                count += turn.size
            }
            val result = system + kept.flatten()
            rejectDroppedRequired(items, result)
            return result
        }

        private fun rejectDroppedRequired(original: List<ModelItem>, kept: List<ModelItem>) {
            val keptRefs = kept.map { it.ref }.toHashSet()
            val dropped = original.filter { item ->
                item.ref !in keptRefs &&
                    item is ModelItem.ProviderItem &&
                    item.replay == ReplayPolicy.Required
            }
            if (dropped.isEmpty()) return
            val kinds = dropped.joinToString { (it as ModelItem.ProviderItem).kind }
            throw AgentFrameworkException(
                AgentError.PreparationError(
                    component = "memory",
                    message = "window dropped ReplayPolicy.Required item(s): $kinds",
                ),
            )
        }

        private fun groupIntoTurns(items: List<ModelItem>): List<List<ModelItem>> {
            val turns = mutableListOf<MutableList<ModelItem>>()
            for (item in items) {
                if (item.isUserTurnBoundary() || turns.isEmpty()) {
                    turns += mutableListOf(item)
                } else {
                    turns.last() += item
                }
            }
            return turns
        }
    }
}

class WindowMemoryProvider(
    val maxMessages: Int,
    val retention: TurnRetention = TurnRetention.Interrupted,
) : MemoryProvider {
    override val id: MemoryProviderId = MemoryProviderId("window:$maxMessages")
    override suspend fun open(thread: ThreadId): ThreadMemory = WindowMemory(maxMessages, retention)
}
