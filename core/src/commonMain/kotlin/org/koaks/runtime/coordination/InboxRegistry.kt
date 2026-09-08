package org.koaks.runtime.coordination

import kotlinx.coroutines.flow.MutableStateFlow
import org.koaks.runtime.run.RunId

/** Owns runtime-local inboxes and closes them with the runtime. */
internal class InboxRegistry {
    private data class State(val boxes: Map<RunId, Inbox> = emptyMap(), val closed: Boolean = false)
    private val state = MutableStateFlow(State())

    fun get(id: RunId): Inbox {
        val created = Inbox(id)
        while (true) {
            val current = state.value
            check(!current.closed) { "Coordination is closed" }
            current.boxes[id]?.let { return it }
            if (state.compareAndSet(current, current.copy(boxes = current.boxes + (id to created)))) return created
        }
    }

    fun remove(id: RunId) {
        while (true) {
            val current = state.value
            val box = current.boxes[id] ?: return
            if (state.compareAndSet(current, current.copy(boxes = current.boxes - id))) {
                box.close()
                return
            }
        }
    }

    fun close() {
        while (true) {
            val current = state.value
            if (current.closed) return
            if (state.compareAndSet(current, State(closed = true))) {
                current.boxes.values.forEach { it.close() }
                return
            }
        }
    }
}
