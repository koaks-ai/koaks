package org.koaks.framework.model

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first

/**
 * The single model-layer primitive: [stream] always returns a cold [Flow] of
 * [ModelEvent] whose last event is [ModelEvent.Finished].
 *
 * Implementations only decode wire frames into model events; they never produce
 * tool results, step, or terminal agent-loop events.
 */
interface LanguageModel {
    val capabilities: ModelCapabilities
    fun stream(request: ModelRequest): Flow<ModelEvent>

    /** Best-effort release of a server-side response (e.g. OpenAI `responses.cancel`). */
    suspend fun abandon(responseId: String) {}
}

suspend fun LanguageModel.generate(request: ModelRequest): ModelResponse =
    stream(request).filterIsInstance<ModelEvent.Finished>().first().response
