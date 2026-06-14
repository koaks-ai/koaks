package org.koaks.framework.loop

import org.koaks.framework.model.AgentError

/**
 * Loop-internal carrier: converts a provider-reported [org.koaks.framework.model.ModelEvent.Failed]
 * into an exception so we can break out of `collect`, while carrying the original
 * [AgentError] verbatim — avoiding a lossy AgentError → Throwable → AgentError round
 * trip. Visible only inside the loop.
 */
internal class ModelFailure(val error: AgentError) : Exception(error.message, error.cause)
