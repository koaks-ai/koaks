package org.koaks.framework.provider

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.koaks.framework.model.AgentError
import org.koaks.framework.model.AgentFrameworkException
import org.koaks.framework.model.LanguageModel
import org.koaks.framework.model.ModelEvent
import org.koaks.framework.model.ModelRequest
import org.koaks.framework.model.ModelResponse
import org.koaks.framework.transport.ModelTransport
import org.koaks.framework.transport.WireCall
import org.koaks.framework.transport.WireFrame

/**
 * Base class for HTTP providers. A provider implements [toWireCall] and [newDecoder];
 * [stream] is `final` and guarantees a trailing [ModelEvent.Finished].
 */
abstract class ChatModel(
    val config: ModelConfig,
    protected val transport: ModelTransport,
) : LanguageModel {

    protected abstract fun toWireCall(req: ModelRequest): WireCall

    protected abstract fun newDecoder(): WireDecoder

    protected open fun newDecoder(request: ModelRequest): WireDecoder = newDecoder()

    /** Provider-owned request orchestration; defaults to one transport call. */
    protected open fun wireFrames(request: ModelRequest): Flow<WireFrame> =
        transport.call(toWireCall(request))

    final override fun stream(request: ModelRequest): Flow<ModelEvent> = flow {
        val decoder = newDecoder(request)
        var finished = false
        try {
            wireFrames(request).collect { frame ->
                decoder.accept(frame).forEach { event ->
                    if (event is ModelEvent.Finished) finished = true
                    emit(event)
                }
            }
            decoder.finish().forEach { event ->
                if (event is ModelEvent.Finished) finished = true
                emit(event)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            if (!finished) {
                emit(
                    ModelEvent.Finished(
                        ModelResponse.Failed(
                            error = failure.toModelError(),
                        ),
                    ),
                )
                finished = true
            } else {
                throw failure
            }
        }
        if (!finished) {
            emit(
                ModelEvent.Finished(
                    ModelResponse.Failed(
                        error = AgentError.ModelError(
                            message = "model stream ended without a terminal response",
                            retriable = false,
                        ),
                    ),
                ),
            )
        }
    }
}

private fun Throwable.toModelError(): AgentError.ModelError = when (this) {
    is AgentFrameworkException -> error as? AgentError.ModelError
        ?: AgentError.ModelError(error.message, retriable = false, cause = this)
    else -> AgentError.ModelError(
        message = message ?: "model call failed",
        retriable = false,
        cause = this,
    )
}
