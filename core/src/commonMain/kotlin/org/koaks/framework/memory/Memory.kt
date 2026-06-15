package org.koaks.framework.memory

import kotlin.jvm.JvmInline
import org.koaks.framework.model.Message

/**
 * A conversation thread identifier. Carried by a [Thread]; replaces the old
 * hand-passed `memoryId`.
 */
@JvmInline
value class ThreadId(val value: String)

/**
 * Pluggable conversation memory.
 *
 * Strict data-flow contract:
 *  - [load] returns the "view fed to the model" — trimming/summarizing happens
 *    HERE (load side), never on commit.
 *  - [commit] faithfully appends this run's successful messages — it never trims.
 *  - The agent loop never touches Memory; only [Thread] calls load/commit at the
 *    run boundary.
 */
interface Memory {
    suspend fun load(thread: ThreadId): List<Message>
    suspend fun commit(thread: ThreadId, messages: List<Message>)
}

/** No persistence — every run starts empty. The core default. */
object NoMemory : Memory {
    override suspend fun load(thread: ThreadId): List<Message> = emptyList()
    override suspend fun commit(thread: ThreadId, messages: List<Message>) {}
}
