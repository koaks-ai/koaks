package org.koaks.graph

class GraphEngine(
    private val graph: StateGraph,
    private val config: EngineRunnableConfig = EngineRunnableConfig()
) {
    private val router = EdgeRouter(graph)
    private val interceptors = mutableListOf<NodeInterceptor>()

    fun addInterceptor(interceptor: NodeInterceptor): GraphEngine = apply {
        interceptors.add(interceptor)
    }

    suspend fun execute(): ExecutionResult {
        val context = graph.context

        return try {
            executeGraph(context)
            ExecutionResult.Success(context.snapshot())
        } catch (e: GraphException) {
            ExecutionResult.Failure(e, context.snapshot())
        } catch (e: Exception) {
            ExecutionResult.Failure(
                GraphException("Unexpected error: ${e.message}", e),
                context.snapshot()
            )
        }
    }

    private suspend fun executeGraph(context: GraphContext) {
        while (!context.isFinished()) {
            checkLimits(context)

            val nodeId = context.currentNode()
            val node = graph.getNode(nodeId)
                ?: throw GraphException("Node not found: $nodeId")

            executeNode(context, node)

            val nextNode = router.route(nodeId, context) ?: StateGraph.END
            context.moveTo(nextNode)
        }
    }

    private suspend fun executeNode(context: GraphContext, node: Node) {
        interceptors.forEach { it.beforeNode(context, node) }

        try {
            // START 和 END 节点不执行 action
            if ((node.type != NodeType.START) && (node.type != NodeType.END)) {
                node.action(context)
            }
        } catch (e: Exception) {
            interceptors.forEach { it.onError(context, node, e) }
            throw GraphException("Error in node ${node.id}: ${e.message}", e)
        }

        interceptors.forEach { it.afterNode(context, node) }
    }

    private fun checkLimits(context: GraphContext) {
        if (context.iteration() >= config.maxIterations) {
            throw GraphException(
                "Max iterations exceeded: ${config.maxIterations}"
            )
        }
    }

}

sealed class ExecutionResult {
    data class Success(val state: Map<String, Any>) : ExecutionResult()
    data class Failure(
        val error: GraphException,
        val stateAtFailure: Map<String, Any>
    ) : ExecutionResult()
}
