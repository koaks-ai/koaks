package org.koaks.graph

class EdgeRouter(graph: StateGraph) {

    private val edgeIndex: Map<String, List<Edge>> = graph.getAllEdges()
        .groupBy { it.from }

    suspend fun route(from: String, context: GraphContext): String? {
        val edges = edgeIndex[from] ?: return null

        // 直接边优先级最高 (DirectEdge)
        edges.firstOrNull { it is DirectEdge }?.let {
            return it.to
        }

        // 找到该节点出发的 ConditionalEdge
        edges.filterIsInstance<ConditionalEdge>().firstOrNull()?.let { edge ->
            val key = edge.router(context)
            return edge.cases[key]
        }

        return null
    }
}