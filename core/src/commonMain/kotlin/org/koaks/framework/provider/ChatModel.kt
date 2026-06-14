package org.koaks.framework.provider

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.koaks.framework.model.ChatRequest
import org.koaks.framework.model.LanguageModel
import org.koaks.framework.model.ModelEvent
import org.koaks.framework.transport.ModelConfig
import org.koaks.framework.transport.Transport
import org.koaks.framework.transport.WireAdapter

/**
 * Base class for provider implementations. A provider only implements [toWire],
 * [adapter], [newDecoder] and [capabilities] — it is completely decoupled from the
 * agent loop.
 *
 * [generate] is `final`: it drives the [Transport] stream through a fresh stateful
 * [WireDecoder] per call (`accept` per chunk, then `finish` to flush residue).
 *
 * @param TReq the provider's wire request type.
 * @param TResp the provider's wire response (chunk) type.
 */
abstract class ChatModel<TReq, TResp>(
    val config: ModelConfig,
    protected val transport: Transport,
) : LanguageModel {

    protected abstract val adapter: WireAdapter<TReq, TResp>

    /** Translates the unified request into the provider's wire format. */
    protected abstract fun toWire(req: ChatRequest): TReq

    /** Creates a fresh stateful decoder for one [generate] call. */
    protected abstract fun newDecoder(): WireDecoder<TResp>

    final override fun generate(request: ChatRequest): Flow<ModelEvent> = flow {
        val decoder = newDecoder()
        transport.stream(config, toWire(request), adapter).collect { chunk ->
            decoder.accept(chunk).forEach { emit(it) }
        }
        decoder.finish().forEach { emit(it) }
    }
}
