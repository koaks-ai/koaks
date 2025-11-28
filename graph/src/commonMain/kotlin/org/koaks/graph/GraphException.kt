package org.koaks.graph

open class GraphException(message: String, cause: Throwable? = null)
    : RuntimeException(message, cause)

class NodeExecutionException(
    val nodeId: String,
    cause: Throwable
) : GraphException("Error in node: $nodeId", cause)

class RoutingException(
    val from: String,
    val key: String,
    val availableKeys: Set<String>
) : GraphException(
    "ConditionalEdge routing error from '$from' for key '$key'. " +
            "Available mappings: $availableKeys"
)

class MissingRouteException(
    val from: String
) : GraphException("No outgoing edge from '$from'")