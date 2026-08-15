package org.koaks.framework.model

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Tries each [LanguageModel] in order. A later model is used ONLY when the
 * preceding one fails before any event reaches the consumer. Once a single event
 * has been emitted the active model is committed.
 *
 * Native ids belonging to a previous provider are stripped on fallback so they
 * cannot be echoed to a different provider.
 */
internal class FallbackModel(
    private val models: List<LanguageModel>,
) : LanguageModel {

    init {
        require(models.isNotEmpty()) { "FallbackModel requires at least one model" }
    }

    override val capabilities: ModelCapabilities = models.first().capabilities

    override fun stream(request: ModelRequest): Flow<ModelEvent> = flow {
        var lastError: AgentError.ModelError? = null

        for ((index, model) in models.withIndex()) {
            val isLast = index == models.lastIndex
            var emitted = false
            val scoped = if (index == 0) request else request.withoutForeignNativeIds()
            try {
                model.stream(scoped).collect { event ->
                    if (event is ModelEvent.Finished &&
                        event.response is ModelResponse.Failed &&
                        !emitted &&
                        !isLast
                    ) {
                        lastError = event.response.error
                        throw FallbackSignal
                    }
                    emitted = true
                    emit(event)
                }
                return@flow
            } catch (signal: FallbackSignal) {
                continue
            } catch (ce: CancellationException) {
                throw ce
            } catch (afe: AgentFrameworkException) {
                val error = afe.error as? AgentError.ModelError
                    ?: AgentError.ModelError(afe.error.message, retriable = false, cause = afe)
                emit(ModelEvent.Finished(ModelResponse.Failed(error = error)))
                return@flow
            } catch (t: Throwable) {
                if (emitted || isLast) throw t
                lastError = AgentError.ModelError(
                    message = t.message ?: "model failed before producing output",
                    retriable = true,
                    cause = t,
                )
            }
        }

        lastError?.let {
            emit(ModelEvent.Finished(ModelResponse.Failed(error = it)))
        }
    }

    override suspend fun abandon(responseId: String) {
        models.first().abandon(responseId)
    }

    private object FallbackSignal : Throwable() {
        private fun readResolve(): Any = FallbackSignal
    }
}

internal fun ModelRequest.withoutForeignNativeIds(): ModelRequest {
    items.filterIsInstance<ModelItem.ProviderItem>()
        .filter { it.replay == ReplayPolicy.Required }
        .takeIf { it.isNotEmpty() }
        ?.let { required ->
            throw AgentFrameworkException(
                AgentError.PreparationError(
                    component = "fallback",
                    message = "cannot fall back: ReplayPolicy.Required item(s) ${
                        required.joinToString { it.kind }
                    } cannot leave their native provider",
                ),
            )
        }
    return copy(
        items = items.mapNotNull { item ->
            when (item) {
                is ModelItem.ProviderItem -> null
                else -> item.stripNativeId()
            }
        },
        checkpoint = null,
    )
}

private fun ModelItem.stripNativeId(): ModelItem = when (this) {
    is ModelItem.Message -> copy(nativeId = null)
    is ModelItem.ToolCall -> copy(nativeId = null)
    is ModelItem.ToolResult -> copy(nativeId = null)
    is ModelItem.ReasoningSummary -> copy(nativeId = null)
    is ModelItem.ProviderItem -> this
}
