package org.koaks.framework.memory

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koaks.framework.model.Message
import org.koaks.framework.model.Role

/**
 * Sliding-window memory (core default). Persists every committed message faithfully;
 * trimming happens only on [load].
 *
 * **Turn-atomic trimming**: the window unit is a complete turn, NOT a
 * single message. A turn is a USER message plus every assistant/tool message that
 * follows it until the next USER message. Dropping whole turns guarantees we never
 * orphan a `tool` result from its `assistant` tool-call (which providers reject).
 * Leading system messages are always preserved.
 */
class WindowMemory(private val maxMessages: Int) : Memory {

    private val mutex = Mutex()
    private val store = HashMap<String, MutableList<Message>>()

    override suspend fun load(thread: ThreadId): List<Message> = mutex.withLock {
        val all = store[thread.value] ?: return emptyList()
        dropTurnsToFit(all, maxMessages)
    }

    override suspend fun commit(thread: ThreadId, messages: List<Message>): Unit = mutex.withLock {
        store.getOrPut(thread.value) { mutableListOf() }.addAll(messages)
    }

    internal companion object {
        /**
         * Keeps leading system messages, then drops the oldest whole turns until the
         * total message count fits [max]. If a single turn alone exceeds [max], it is
         * kept intact (correctness over the soft cap — never split a turn).
         */
        fun dropTurnsToFit(messages: List<Message>, max: Int): List<Message> {
            if (messages.size <= max) return messages

            val system = messages.takeWhile { it.role == Role.SYSTEM }
            val rest = messages.drop(system.size)
            val turns = groupIntoTurns(rest)

            // Keep newest turns until adding an older one would exceed the budget.
            val budget = (max - system.size).coerceAtLeast(0)
            val kept = ArrayDeque<List<Message>>()
            var count = 0
            for (turn in turns.asReversed()) {
                if (count + turn.size > budget && kept.isNotEmpty()) break
                kept.addFirst(turn)
                count += turn.size
            }
            return system + kept.flatten()
        }

        /** Groups a system-free message list into turns, each starting at a USER message. */
        private fun groupIntoTurns(messages: List<Message>): List<List<Message>> {
            val turns = mutableListOf<MutableList<Message>>()
            for (msg in messages) {
                if (msg.role == Role.USER || turns.isEmpty()) {
                    turns += mutableListOf(msg)
                } else {
                    turns.last() += msg
                }
            }
            return turns
        }
    }
}
