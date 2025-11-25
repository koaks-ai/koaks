package org.koaks.graph

interface NodeInterceptor {
    suspend fun beforeNode(context: GraphContext, node: Node) {}
    suspend fun afterNode(context: GraphContext, node: Node) {}
    suspend fun onError(context: GraphContext, node: Node, error: Exception) {}
}