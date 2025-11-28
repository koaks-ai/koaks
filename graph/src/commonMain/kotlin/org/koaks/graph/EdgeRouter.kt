package org.koaks.graph

class EdgeRouter(graph: StateGraph) {

    private val edgeIndex: Map<String, List<Edge>> = graph.getAllEdges()
        .groupBy { it.from }

    suspend fun route(from: String, context: GraphContext): Result<String> {
        val edges = edgeIndex[from] ?: return Result.failure(MissingRouteException(from))

        // DirectEdge 优先
        val direct = edges.firstOrNull { it is DirectEdge }
        if (direct is DirectEdge) {
            return Result.success(direct.to)
        }

        // ConditionalEdge
        val conditional = edges.filterIsInstance<ConditionalEdge>().firstOrNull()
            ?: return Result.failure(MissingRouteException(from))

        val key = conditional.router(context)

        // 隐式路由，[不添加边映射]并且[未开启严格模式], 默认映射自身
        val target = conditional.cases[key]
            ?: if (conditional.useImplicitRouting) key else null

        return if (target != null) {
            Result.success(target)
        } else {
            Result.failure(
                RoutingException(from, key, conditional.cases.keys)
            )
        }
    }

}