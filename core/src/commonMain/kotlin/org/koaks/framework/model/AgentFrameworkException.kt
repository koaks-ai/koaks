package org.koaks.framework.model

/**
 * Typed framework failure that can cross internal component boundaries without
 * losing the public [AgentError] classification used by runtime result channels.
 */
open class AgentFrameworkException(
    val error: AgentError,
    cause: Throwable? = error.cause,
) : IllegalStateException(error.message, cause)
