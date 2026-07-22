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
    init {
        require(value.isNotBlank()) { "ThreadId must not be blank" }
    }
}

@JvmInline
value class MemoryProviderId(val value: String) {
    init {
        require(value.isNotBlank()) { "MemoryProviderId must not be blank" }
    }
}

/**
 * The memory partition bound to exactly one Runtime [ThreadId]. Runtime opens it once,
 * then owns its lifecycle until the Runtime closes.
 *
 * Strict data-flow contract:
 *  - [load] returns the view fed to the model; recall/filtering happens here.
 *  - [commit] faithfully appends one successful atomic Turn.
 *  - The agent loop never touches memory; Runtime alone calls load/commit.
 */
interface ThreadMemory : AutoCloseable {
    suspend fun load(query: Message): List<Message>
    suspend fun commit(messages: List<Message>, usage: Usage = Usage.ZERO)
    override fun close() {}
}

/** Stable memory policy carried by an Agent and bound by Runtime when a Thread is created. */
interface MemoryProvider {
    val id: MemoryProviderId
    suspend fun open(thread: ThreadId): ThreadMemory
}

/** Factory-backed provider for application-defined ThreadMemory implementations. */
class FixedMemoryProvider(
    override val id: MemoryProviderId,
    private val opener: suspend (ThreadId) -> ThreadMemory,
) : MemoryProvider {
    override suspend fun open(thread: ThreadId): ThreadMemory = opener(thread)
}

fun memoryProvider(
    id: MemoryProviderId,
    open: suspend (ThreadId) -> ThreadMemory,
): MemoryProvider = FixedMemoryProvider(id, open)

fun memoryProvider(
    id: String,
    open: suspend (ThreadId) -> ThreadMemory,
): MemoryProvider = memoryProvider(MemoryProviderId(id), open)

/** No persistence — every run starts empty. */
object NoMemory : ThreadMemory {
    override suspend fun load(query: Message): List<Message> = emptyList()
    override suspend fun commit(messages: List<Message>, usage: Usage) {}
}

/** Explicit opt-out provider for `memory { none() }`; distinct from using the Runtime default. */
object NoMemoryProvider : MemoryProvider {
    override val id: MemoryProviderId = MemoryProviderId("none")
    override suspend fun open(thread: ThreadId): ThreadMemory = NoMemory
}
