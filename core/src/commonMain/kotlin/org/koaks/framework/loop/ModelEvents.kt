package org.koaks.framework.loop

import org.koaks.framework.model.AgentError
import org.koaks.framework.model.ModelEvent
import org.koaks.framework.model.ModelResponse
import org.koaks.framework.model.Usage

fun done(usage: Usage = Usage.ZERO): ModelEvent =
    ModelEvent.Finished(ModelResponse.Completed(usage = usage))

fun fail(error: AgentError.ModelError): ModelEvent =
    ModelEvent.Finished(ModelResponse.Failed(error = error))

fun fail(message: String, retriable: Boolean = false): ModelEvent =
    fail(AgentError.ModelError(message, retriable))
