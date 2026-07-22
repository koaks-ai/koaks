package org.koaks.memory.summarizing

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koaks.framework.memory.ThreadMemory
import org.koaks.framework.model.ChatRequest
import org.koaks.framework.model.LanguageModel
import org.koaks.framework.model.Message
import org.koaks.framework.model.ModelEvent
import org.koaks.framework.model.Role
import org.koaks.framework.model.Usage

/**
 * Memory that compacts older history once a run's API-measured prompt tokens exceed
 * [maxTokens], using a [LanguageModel] to compress the oldest turns into a single system
 * "summary so far" message. Lives in its own module (`koaks-memory:summarizing`) since it
 * depends on a model — the core stays dependency-free.
 *
 * Compression is **persistent and lossy**: the summarized turns REPLACE the original
 * messages in the store (the raw text is not kept), keeping the store compact and
 * restorable. It happens on [commit], AFTER the response has already been emitted, so it
 * never sits on the run's critical path — but it does block commit's return (the compacted
 * history must be durable before the next run loads it).
 *
 * The token check uses [usage.promptTokens] — the real size of the history+input the model
 * just saw — so the trigger reflects actual context pressure rather than a message count.
 * Turn boundaries are respected so assistant↔toolResult pairings are never split.
 */
class SummarizingMemory(
    private val maxTokens: Int,
    private val model: LanguageModel,
    private val keepRecentTurns: Int = 2,
) : ThreadMemory {

    private val mutex = Mutex()
    private var version: Long = 0
    private var messages: MutableList<Message> = mutableListOf()

    override suspend fun commit(messages: List<Message>, usage: Usage) {
        // 1. Faithfully append this run's messages.
        val (snapshotVersion, snapshot) = mutex.withLock {
            this.messages.addAll(messages)
            version++
            version to this.messages.toList()
        }

        // 2. Only compact when the last run actually exceeded the token budget.
        if (usage.promptTokens <= maxTokens) return

        val system = snapshot.takeWhile { it.role == Role.SYSTEM }
        val rest = snapshot.drop(system.size)
        val turns = groupIntoTurns(rest)
        if (turns.size <= keepRecentTurns) return

        // 3. Summarize the older turns OUTSIDE the lock (model call may be a network hop).
        val toSummarize = turns.dropLast(keepRecentTurns).flatten()
        val recent = turns.takeLast(keepRecentTurns).flatten()
        val summary = summarize(toSummarize)
        val compacted = system + Message.system("Summary of earlier conversation:\n$summary") + recent

        // 4. Replace the persisted history with the compacted view (lossy, in place).
        mutex.withLock {
            // The summarizer ran outside the lock. Never replace a newer snapshot and
            // thereby lose turns committed while that model call was in flight.
            if (version == snapshotVersion) {
                this.messages = compacted.toMutableList()
                version++
            }
        }
    }

    /** Faithful snapshot of the persisted history; compaction already happened on commit. */
    override suspend fun load(query: Message): List<Message> = mutex.withLock { messages.toList() }

    private suspend fun summarize(messages: List<Message>): String {
        val transcript = messages.joinToString("\n") { "${it.role}: ${it.text}" }
        val request = ChatRequest(
            messages = listOf(
                Message.system("Summarize the following conversation concisely, preserving facts, decisions, and open questions."),
                Message.user(transcript),
            ),
            stream = false,
        )
        val events = model.generate(request).toList()
        return events.filterIsInstance<ModelEvent.TextDelta>().joinToString("") { it.text }
            .ifBlank { "(summary unavailable)" }
    }

    private fun groupIntoTurns(messages: List<Message>): List<List<Message>> {
        val turns = mutableListOf<MutableList<Message>>()
        for (msg in messages) {
            if (msg.role == Role.USER || turns.isEmpty()) turns += mutableListOf(msg)
            else turns.last() += msg
        }
        return turns
    }
}
