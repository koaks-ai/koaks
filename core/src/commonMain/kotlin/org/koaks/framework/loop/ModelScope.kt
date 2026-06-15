package org.koaks.framework.loop

import org.koaks.framework.model.LanguageModel
import org.koaks.framework.transport.KtorTransport
import org.koaks.framework.transport.Transport

/**
 * DSL scope for selecting the model. Provider modules attach via extension
 * functions, e.g. `fun ModelScope.qwen(...)`, which build a `ChatModel` using
 * [transport] and set [selected].
 *
 * The scope lazily creates a default [KtorTransport] (owned by the agent) unless an
 * external transport was injected.
 */
@AgentDsl
class ModelScope {
    private var external: Transport? = null
    private var lazyTransport: Transport? = null

    var selected: LanguageModel? = null

    /** Injects an externally-owned transport; the agent will not close it. */
    fun transport(transport: Transport) {
        external = transport
    }

    /**
     * Escape hatch: use a hand-written [LanguageModel] directly (self-hosted
     * model / internal gateway / test mock) instead of a provider DSL.
     */
    fun custom(model: LanguageModel) {
        selected = model
    }

    /** The transport providers should use. Created on demand if none was injected. */
    val transport: Transport
        get() = external ?: lazyTransport ?: KtorTransport().also { lazyTransport = it }

    internal fun ownsTransport(): Boolean = external == null

    internal fun build(): BuiltModel = BuiltModel(
        model = requireNotNull(selected) { "a model is required (e.g. model { qwen(...) })" },
        // Only an already-resolved transport is owned/closed; a custom model never
        // triggers the lazy default, so nothing is created needlessly.
        transport = external ?: lazyTransport,
        ownsTransport = external == null && lazyTransport != null,
    )
}

internal data class BuiltModel(
    val model: LanguageModel,
    val transport: Transport?,
    val ownsTransport: Boolean,
)
