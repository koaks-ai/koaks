package org.koaks.framework.memory

import kotlin.jvm.JvmInline
import org.koaks.framework.model.ModelItem
import org.koaks.framework.model.ProviderCheckpoint
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
 * Strict data-flow contract:
 *  - [load] returns the view fed to the model; recall/filtering happens here.
 *  - [commit] faithfully appends one successful-or-interrupted atomic Turn.
 *  - The agent loop never touches memory; Runtime alone calls load/commit.
 *  - Runtime repairs the loaded transcript and validates [MemoryView.checkpoint]
 *    against the *repaired* view before handing it to the model.
 */
interface ThreadMemory : AutoCloseable {
    val retention: TurnRetention get() = TurnRetention.Interrupted
    suspend fun load(query: List<ModelItem>): MemoryView
    suspend fun commit(turn: ConversationTurn)
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
    override suspend fun commit(turn: ConversationTurn) {}
}

object NoMemoryProvider : MemoryProvider {
    override val id: MemoryProviderId = MemoryProviderId("none")
    override suspend fun open(thread: ThreadId): ThreadMemory = NoMemory
}

fun shouldRetain(turn: ConversationTurn, retention: TurnRetention, sideEffects: Boolean): Boolean =
    when (retention) {
        TurnRetention.Interrupted -> true
        TurnRetention.InterruptedIfSideEffects ->
            turn.status is TurnStatus.Completed || sideEffects
        TurnRetention.CompletedOnly -> turn.status is TurnStatus.Completed
    }

fun turnHasSideEffects(turn: ConversationTurn): Boolean =
    turn.items.any { it is ModelItem.ToolResult && !it.isError }

/** Runtime-facing helper: repair + drop a checkpoint whose basis no longer matches. */
fun MemoryView.forOnlineUse(): MemoryView {
    val repaired = repairTranscript(transcript)
    return MemoryView(
        transcript = repaired,
        checkpoint = checkpoint.takeIfValidFor(repaired),
    )
}
