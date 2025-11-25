package org.koaks.graph

class EdgeRouter(graph: StateGraph) {
    // 预先构建索引,避免每次遍历
    private val edgeIndex: Map<String, List<Edge>> = graph.getAllEdges()
        .groupBy { it.from }
    
    suspend fun route(from: String, context: GraphContext): String? {
        val edges = edgeIndex[from] ?: return null
        
        // 直接边优先级最高
        edges.firstOrNull { it is DirectEdge }?.let { 
            return it.to 
        }
        
        // 按顺序检查条件边
        edges.filterIsInstance<ConditionalEdge>().forEach { edge ->
            if (edge.condition(context)) {
                return edge.to
            }
        }
        
        return null
    }
}