package org.koaks.framework.memory

import kotlin.jvm.JvmInline
import org.koaks.framework.model.Message
import org.koaks.framework.model.Usage

/**
 * A conversation thread identifier. Carried as the memory key by `Agent.run`/`stream`;
 * replaces the old hand-passed `memoryId`.
 */
@JvmInline
value class ThreadId(val value: String) {
    companion object {
        /** Used when a memory-backed run does not name a thread. */
        val DEFAULT = ThreadId("default")
    }
}

/**
 * Pluggable conversation memory.
 *
 * Strict data-flow contract:
 *  - [load] returns the "view fed to the model" — any recall/filtering happens HERE.
 *  - [commit] faithfully appends this run's successful messages. It receives [usage],
 *    the API-measured token counts for the run that just reached a terminal state
 *    ([usage.promptTokens] is the real size of the history+input the model saw), so a
 *    compressing implementation can decide — AFTER the response is already emitted —
 *    whether to compact the persisted history for the next run.
 *  - The agent loop never touches Memory; only `Agent.run`/`stream` call load/commit at
 *    the run boundary.
 */
interface Memory {
    suspend fun load(thread: ThreadId): List<Message>
    suspend fun commit(thread: ThreadId, messages: List<Message>, usage: Usage = Usage.ZERO)
}

/** No persistence — every run starts empty. The core default. */
object NoMemory : Memory {
    override suspend fun load(thread: ThreadId): List<Message> = emptyList()
    override suspend fun commit(thread: ThreadId, messages: List<Message>, usage: Usage) {}
}
