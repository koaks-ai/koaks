package org.koaks.framework.middleware

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.koaks.framework.model.ModelEvent

/**
 * A short-circuiting cache middleware (design §3.7). On a cache hit it returns a
 * self-made flow of the cached [ModelEvent]s and NEVER calls `next()`, so the model
 * is not hit. On a miss it returns `next()` verbatim (the loop is the sole consumer)
 * and records the events as they flow past via [AgentListener.onModelEvent].
 *
 * The key is derived from the working-set messages. This is an in-memory cache; swap
 * [store] for a persistent one as needed.
 */
class Cache(
    private val store: MutableMap<String, List<ModelEvent>> = mutableMapOf(),
    private val keyOf: (StepContext) -> String = { it.state.messages.joinToString("") { m -> "${m.role}:${m.text}" } },
) : AgentMiddleware, AgentListener {

    // Tracks the key for the in-flight (uncached) step so the listener can record it.
    private var pendingKey: String? = null
    private val recording = mutableListOf<ModelEvent>()

    override suspend fun aroundModelCall(
        ctx: StepContext,
        next: suspend () -> Flow<ModelEvent>,
    ): Flow<ModelEvent> {
        val key = keyOf(ctx)
        store[key]?.let { cached ->
            pendingKey = null
            return flowOf(*cached.toTypedArray())   // hit: short-circuit, model untouched
        }
        // Miss: let the loop consume the real flow; record events via the listener.
        pendingKey = key
        recording.clear()
        return next()
    }

    override fun onModelEvent(event: ModelEvent) {
        if (pendingKey != null) {
            recording += event
            // A terminal model event closes the recording for this step.
            if (event is ModelEvent.Completed || event is ModelEvent.Failed) {
                store[pendingKey!!] = recording.toList()
                pendingKey = null
            }
        }
    }
}
