package org.koaks.framework.model

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Wraps an ordered list of [LanguageModel]s, trying each in turn. A later model is
 * used ONLY when the preceding one fails *before any event reaches the consumer*
 * (connection refused, auth rejection, first-packet timeout) — the same boundary
 * [AgentError.ModelError.retriable] draws. Once a single event has been emitted
 * downstream the active model is committed, and any failure propagates unchanged:
 * we cannot un-send tokens a consumer has already observed.
 *
 * [capabilities] reports the primary model's capabilities. Fallbacks are expected to
 * be capability-compatible with the primary; declare them so in their own DSL.
 */
internal class FallbackModel(
    private val models: List<LanguageModel>,
) : LanguageModel {

    init {
        require(models.isNotEmpty()) { "FallbackModel requires at least one model" }
    }

    override val capabilities: ModelCapabilities = models.first().capabilities

    override fun generate(request: ChatRequest): Flow<ModelEvent> = flow {
        var lastError: AgentError.ModelError? = null

        for ((index, model) in models.withIndex()) {
            val isLast = index == models.lastIndex
            var emitted = false
            try {
                model.generate(request).collect { event ->
                    // A Failed event before first output is a pre-emission failure:
                    // record it and let the next model take over (unless we're last).
                    if (event is ModelEvent.Failed && !emitted && !isLast) {
                        lastError = event.error
                        throw FallbackSignal
                    }
                    emitted = true
                    emit(event)
                }
                return@flow // model completed (success, or a committed/last failure already emitted)
            } catch (signal: FallbackSignal) {
                continue // pre-emission Failed event: try the next model
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                if (emitted || isLast) throw t // committed mid-stream, or nothing left to try
                lastError = AgentError.ModelError(
                    message = t.message ?: "model failed before producing output",
                    retriable = true,
                    cause = t,
                )
                // fall through to the next model
            }
        }

        // Exhausted every model without producing output: surface the last failure.
        lastError?.let { emit(ModelEvent.Failed(it)) }
    }

    /** Internal control-flow marker for "primary failed pre-emission, advance to fallback". */
    private object FallbackSignal : Throwable() {
        private fun readResolve(): Any = FallbackSignal
    }
}
