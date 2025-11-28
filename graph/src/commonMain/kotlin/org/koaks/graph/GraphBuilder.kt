package org.koaks.graph

class StateGraphBuilder(name: String) {
    private val graph = StateGraph(name)

    val start: String get() = StateGraph.START
    val end: String get() = StateGraph.END

    fun model(id: String, handler: suspend (GraphContext) -> Unit): String {
        graph.addNode(Node(id, NodeType.MODEL, handler))
        return id
    }

    fun tool(id: String, handler: suspend (GraphContext) -> Unit): String {
        graph.addNode(Node(id, NodeType.TOOL, handler))
        return id
    }

    fun node(id: String, handler: suspend (GraphContext) -> Unit): String {
        graph.addNode(Node(id, NodeType.NORMAL, handler))
        return id
    }

    infix fun String.to(target: String): String {
        graph.addEdge(DirectEdge(this, target))
        return target
    }

    // 条件路由
    fun conditional(
        from: String,
        router: suspend (GraphContext) -> String,
        block: ConditionalEdgeBuilder.() -> Unit
    ) {
        val builder = ConditionalEdgeBuilder(from, router)
        builder.block()
        builder.build().let { graph.addEdge(it) }
    }

    fun build(): StateGraph {
        val validation = graph.validate()
        if (validation is ValidationResult.Invalid) {
            throw IllegalStateException(
                "Graph validation failed:\n${validation.errors.joinToString("\n")}"
            )
        }
        return graph
    }
}

fun createGraph(name: String, block: StateGraphBuilder.() -> Unit): StateGraph {
    return StateGraphBuilder(name).apply(block).build()
}
