package org.koaks.memory.summarizing

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koaks.framework.memory.Memory
import org.koaks.framework.memory.ThreadId
import org.koaks.framework.model.ChatRequest
import org.koaks.framework.model.LanguageModel
import org.koaks.framework.model.Message
import org.koaks.framework.model.ModelEvent
import org.koaks.framework.model.Role

/**
 * Memory that summarizes older history once it exceeds [maxMessages], using a
 * [LanguageModel] to compress the oldest turns into a single system "summary so far"
 * message. Lives in its own module (`koaks-memory:summarizing`) since it depends on a
 * model — the core stays dependency-free (design §4.5 module placement).
 *
 * Trimming/summarizing happens on [load] only; [commit] faithfully appends (§4.5).
 * Turn boundaries are respected so assistant↔toolResult pairings are never split.
 */
class SummarizingMemory(
    private val maxMessages: Int,
    private val model: LanguageModel,
    private val keepRecentTurns: Int = 2,
) : Memory {

    private val mutex = Mutex()
    private val store = HashMap<String, MutableList<Message>>()

    override suspend fun commit(thread: ThreadId, messages: List<Message>): Unit = mutex.withLock {
        store.getOrPut(thread.value) { mutableListOf() }.addAll(messages)
    }

    override suspend fun load(thread: ThreadId): List<Message> {
        val all = mutex.withLock { store[thread.value]?.toList() } ?: return emptyList()
        if (all.size <= maxMessages) return all

        val system = all.takeWhile { it.role == Role.SYSTEM }
        val rest = all.drop(system.size)
        val turns = groupIntoTurns(rest)
        if (turns.size <= keepRecentTurns) return all

        val toSummarize = turns.dropLast(keepRecentTurns).flatten()
        val recent = turns.takeLast(keepRecentTurns).flatten()
        val summary = summarize(toSummarize)

        return system + Message.system("Summary of earlier conversation:\n$summary") + recent
    }

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
