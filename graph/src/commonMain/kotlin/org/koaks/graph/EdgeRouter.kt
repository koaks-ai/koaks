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
            // 隐式路由，[不添加边映射]并且[未开启严格模式], 默认映射自身
            return edge.cases[key] ?: if (edge.useImplicitRouting) {
                key
            } else {
                throw GraphException(
                    "Conditional edge from '$from' returned unmapped key '$key'. " +
                            "Available mappings: ${edge.cases.keys}"
                )
            }
        }

        return null
    }
}