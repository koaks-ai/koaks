package org.koaks.framework.memory

import kotlinx.datetime.Clock
import kotlin.jvm.JvmInline
import org.koaks.framework.model.ModelItem
import org.koaks.framework.model.takeIfValidFor

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
 *  - [load] returns the view fed to the model; recall/filtering happens here.
 *  - [append] upserts one completed item (same [ModelItem.ref] replaces in place).
 *  - The agent loop loads before each model call and appends each finished message.
 */
interface ThreadMemory : AutoCloseable {
    suspend fun load(query: List<ModelItem>): MemoryView
    suspend fun append(item: ModelItem)
    override fun close() {}
}

interface MemoryProvider {
    val id: MemoryProviderId
    suspend fun open(thread: ThreadId): ThreadMemory
}

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

object NoMemory : ThreadMemory {
    override suspend fun load(query: List<ModelItem>): MemoryView = MemoryView.EMPTY
    override suspend fun append(item: ModelItem) {}
}

object NoMemoryProvider : MemoryProvider {
    override val id: MemoryProviderId = MemoryProviderId("none")
    override suspend fun open(thread: ThreadId): ThreadMemory = NoMemory
}

fun MutableList<ModelItem>.upsert(item: ModelItem) {
    val index = indexOfFirst { it.ref == item.ref }
    if (index >= 0) this[index] = item else add(item)
}

/** Runtime-facing helper: repair + drop a checkpoint whose basis no longer matches. */
fun MemoryView.forOnlineUse(nowEpochMs: Long = Clock.System.now().toEpochMilliseconds()): MemoryView {
    val repaired = repairTranscript(transcript)
    return MemoryView(
        transcript = repaired,
        checkpoint = checkpoint.takeIfValidFor(repaired, nowEpochMs),
    )
}
