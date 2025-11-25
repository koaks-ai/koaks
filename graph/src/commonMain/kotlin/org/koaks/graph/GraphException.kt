package org.koaks.graph

class GraphException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)