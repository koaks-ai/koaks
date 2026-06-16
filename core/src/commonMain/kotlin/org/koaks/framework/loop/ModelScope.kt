package org.koaks.framework.loop

import org.koaks.framework.model.FallbackModel
import org.koaks.framework.model.LanguageModel
import org.koaks.framework.transport.KtorTransport
import org.koaks.framework.transport.Transport

/**
 * DSL scope for selecting the model. Provider modules attach via extension
 * functions, e.g. `fun ModelScope.qwen(...): ModelSelection`, which build a
 * `ChatModel` using [transport] and return it wrapped in a [ModelSelection].
 *
 * The `model { }` block returns the chosen [ModelSelection] (its last expression):
 *
 * ```
 * model { qwen(url, key, "qwen3") }                      // single model
 * model { qwen(url, key, "qwen3").fallback(ollama(...)) } // primary + fallback
 * ```
 *
 * Returning a value (rather than mutating shared state) is what makes `.fallback()`
 * chainable.
 *
 * The scope lazily creates a default [KtorTransport] (owned by the agent) unless an
 * external transport was injected.
 */
@AgentDSL
class ModelScope {
    private var external: Transport? = null
    private var lazyTransport: Transport? = null

    /** Injects an externally-owned transport; the agent will not close it. */
    fun transport(transport: Transport) {
        external = transport
    }

    /**
     * Escape hatch: use a hand-written [LanguageModel] directly (self-hosted
     * model / internal gateway / test mock) instead of a provider DSL.
     */
    fun custom(model: LanguageModel): ModelSelection = ModelSelection(model)

    /** The transport providers should use. Created on demand if none was injected. */
    val transport: Transport
        get() = external ?: lazyTransport ?: KtorTransport().also { lazyTransport = it }

    internal fun transportInfo(): TransportInfo = TransportInfo(
        // Only an already-resolved transport is owned/closed; a custom model never
        // triggers the lazy default, so nothing is created needlessly.
        transport = external ?: lazyTransport,
        ownsTransport = external == null && lazyTransport != null,
    )
}

/**
 * A selected model, optionally backed by ordered fallbacks. Returned by provider
 * DSL functions; chain alternatives with [fallback].
 */
class ModelSelection internal constructor(
    internal val models: List<LanguageModel>,
) {
    constructor(model: LanguageModel) : this(listOf(model))

    /**
     * Appends [next] (and any of its own fallbacks) as a lower-priority alternative.
     * At runtime a fallback is only tried if the preceding model fails *before any
     * event reaches the consumer*; once output has started, the failure propagates.
     */
    fun fallback(next: ModelSelection): ModelSelection =
        ModelSelection(models + next.models)

    internal fun toModel(): LanguageModel =
        models.singleOrNull() ?: FallbackModel(models)
}

internal data class TransportInfo(
    val transport: Transport?,
    val ownsTransport: Boolean,
)
