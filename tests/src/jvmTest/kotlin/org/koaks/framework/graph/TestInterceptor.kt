package org.koaks.framework.graph

import org.koaks.graph.GraphContext
import org.koaks.graph.Node
import org.koaks.graph.NodeInterceptor

class TestInterceptor : NodeInterceptor {
    override suspend fun beforeNode(context: GraphContext, node: Node) {
        println("Before node: ${node.id}")
    }

    override suspend fun onError(context: GraphContext, node: Node, error: Exception) {
        println("Error in node: ${node.id}")
    }

    override suspend fun afterNode(context: GraphContext, node: Node) {
        println("After node: ${node.id}")
    }
}