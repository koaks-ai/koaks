package org.koaks.memory.summarizing

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koaks.framework.memory.ConversationTurn
import org.koaks.framework.memory.MemoryView
import org.koaks.framework.memory.ThreadMemory
import org.koaks.framework.memory.TurnRetention
import org.koaks.framework.memory.shouldRetain
import org.koaks.framework.memory.turnHasSideEffects
import org.koaks.framework.model.LanguageModel
import org.koaks.framework.model.ModelItem
import org.koaks.framework.model.ModelRequest
import org.koaks.framework.model.ReplayPolicy
import org.koaks.framework.model.displayText
import org.koaks.framework.model.generate
import org.koaks.framework.model.isSystem
import org.koaks.framework.model.isUserTurnBoundary
import org.koaks.framework.model.newIdempotencyKey

/**
 * Memory that compacts older history once a run's API-measured prompt tokens exceed
 * [maxTokens], using a [LanguageModel] to compress the oldest turns into a single system
 * "summary so far" item. Lives in its own module (`koaks-memory:summarizing`) since it
 * depends on a model — the core stays dependency-free.
 *
 * Compression is **persistent and lossy**: the summarized turns REPLACE the original
 * items in the store. [ReplayPolicy.Required] provider items are never summarized away.
 */
class SummarizingMemory(
    private val maxTokens: Int,
    private val model: LanguageModel,
    private val keepRecentTurns: Int = 2,
    override val retention: TurnRetention = TurnRetention.Interrupted,
) : ThreadMemory {

    private val mutex = Mutex()
    private var version: Long = 0
    private var items: MutableList<ModelItem> = mutableListOf()
    private var checkpoint: org.koaks.framework.model.ProviderCheckpoint? = null

    override suspend fun commit(turn: ConversationTurn) {
        val (snapshotVersion, snapshot) = mutex.withLock {
            if (!shouldRetain(turn, retention, turnHasSideEffects(turn))) {
                return
            }
            this.items.addAll(turn.items)
            if (turn.checkpoint != null) this.checkpoint = turn.checkpoint
            version++
            version to this.items.toList()
        }

        if (turn.usage.promptTokens <= maxTokens) return

        val system = snapshot.takeWhile { it.isSystem() }
        val rest = snapshot.drop(system.size)
        val turns = groupIntoTurns(rest)
        if (turns.size <= keepRecentTurns) return

        val toSummarize = turns.dropLast(keepRecentTurns).flatten()
        val recent = turns.takeLast(keepRecentTurns).flatten()
        val required = toSummarize.filterIsInstance<ModelItem.ProviderItem>()
            .filter { it.replay == ReplayPolicy.Required }
        val summary = summarize(toSummarize)
        val compacted = system +
            listOf(ModelItem.system("Summary of earlier conversation:\n$summary")) +
            required +
            recent

        mutex.withLock {
            if (version == snapshotVersion) {
                this.items = compacted.toMutableList()
                this.checkpoint = null
                version++
            }
        }
    }

    override suspend fun load(query: List<ModelItem>): MemoryView = mutex.withLock {
        MemoryView(items.toList(), checkpoint)
    }

    private suspend fun summarize(items: List<ModelItem>): String {
        val transcript = items.joinToString("\n") { it.displayText() }
        val request = ModelRequest(
            instructions = "Summarize the following conversation concisely, preserving facts, decisions, and open questions.",
            items = listOf(ModelItem.user(transcript)),
            idempotencyKey = newIdempotencyKey(),
        )
        val response = model.generate(request)
        val text = response.output.filterIsInstance<ModelItem.Message>().joinToString("") { it.text }
            .ifBlank { items.filterIsInstance<ModelItem.Message>().joinToString("") { it.text } }
        return text.ifBlank { "(summary unavailable)" }
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
