package org.koaks.framework.model

import kotlinx.coroutines.flow.Flow

/**
 * The single model-layer primitive: [generate] always returns a cold
 * [Flow] of [ModelEvent]. Non-streaming is just a flow with few events.
 *
 * Implementations (see `ChatModel`) only decode wire chunks into model events;
 * they never produce tool results, step, or finished events — those belong to
 * the agent loop.
 */
interface LanguageModel {
    val capabilities: ModelCapabilities
    fun generate(request: ChatRequest): Flow<ModelEvent>
}
