package org.koaks.graph

import io.github.oshai.kotlinlogging.KotlinLogging

class GraphEngine(
    private val graph: StateGraph,
    private val config: EngineRunnableConfig = EngineRunnableConfig()
) {
    private val logger = KotlinLogging.logger {}

    private val router = EdgeRouter(graph)
    private val interceptors = mutableListOf<NodeInterceptor>()

    fun addInterceptor(interceptor: NodeInterceptor): GraphEngine = apply {
        interceptors.add(interceptor)
    }

    suspend fun execute() {
        val context = graph.context
        try {
            logger.debug { "[${graph.name}] starting execution" }
            executeGraph(context)
            logger.info { "[${graph.name}] execution completed successfully" }
        } catch (e: Exception) {
            logger.error(e) { "[${graph.name}] execution failed" }
            throw e
        }
    }

    private suspend fun executeGraph(context: GraphContext) {
        while (!context.isFinished()) {
            checkLimits(context)

            val nodeId = context.currentNode()
            val node = graph.getNode(nodeId)

            executeNode(context, node)

            val nextNode = router.route(nodeId, context).getOrThrow()
            context.moveTo(nextNode)
        }
    }

    private suspend fun executeNode(context: GraphContext, node: Node) {
        interceptors.forEach {
            try {
                it.beforeNode(context, node)
            } catch (e: Exception) {
                logger.error(e) { "Interceptor failed: beforeNode" }
            }
        }

        try {
            if (node.type != NodeType.START && node.type != NodeType.END) {
                node.action(context)
            }
        } catch (e: Exception) {
            interceptors.forEach {
                try {
                    it.onError(context, node, e)
                } catch (interceptorError: Exception) {
                    logger.error(interceptorError) { "Interceptor failed: onError" }
                }
            }
            throw NodeExecutionException(node.id, e)
        }

        interceptors.forEach {
            try {
                it.afterNode(context, node)
            } catch (e: Exception) {
                logger.error(e) { "Interceptor failed: afterNode" }
            }
        }
    }

    private fun checkLimits(context: GraphContext) {
        if (context.iteration() >= config.maxIterations) {
            throw GraphException(
                "Max iterations exceeded: ${config.maxIterations}"
            )
        }
    }

}
