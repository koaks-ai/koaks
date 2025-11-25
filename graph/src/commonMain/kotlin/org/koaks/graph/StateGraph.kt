package org.koaks.graph

import kotlin.collections.mutableListOf

class StateGraph(
    val name: String,
    val initialState: Map<String, Any> = emptyMap()
) {

    val context = GraphContext(initialState)

    private val nodes = mutableMapOf<String, Node>()
    private val edges = mutableListOf<Edge>()

    companion object {
        const val START = "__START__"
        const val END = "__END__"
    }

    init {
        addNode(Node(START, NodeType.START))
        addNode(Node(END, NodeType.END))
    }

    fun addNode(node: Node): StateGraph = apply {
        if (node.id in nodes) {
            throw IllegalArgumentException("[$name] -- Duplicate node: ${node.id}")
        }
        nodes[node.id] = node
    }

    fun addEdge(edge: Edge): StateGraph = apply {
        edges.add(edge)
    }

    fun getNode(id: String): Node? = nodes[id]

    fun getAllNodes(): List<Node> = nodes.values.toList()

    fun getAllEdges(): List<Edge> = edges.toList()

    // 返回详细的验证结果
    fun validate(): ValidationResult {
        val errors = mutableListOf<String>()

        // 检查边引用的节点是否存在
        edges.forEach { edge ->
            if (edge.from !in nodes) {
                errors.add("[$name] --  Edge from non-existent node: ${edge.from}")
            }
            if (edge.to !in nodes) {
                errors.add("[$name] -- Edge to non-existent node: ${edge.to}")
            }
        }

        // 检查 START 节点必须有出边
        if (edges.none { it.from == START }) {
            errors.add("[$name] -- START node has no outgoing edges")
        }

        // 检查孤立节点 (除了 START 和 END)
        val connectedNodes = edges.flatMap { listOf(it.from, it.to) }.toSet()
        nodes.keys.forEach { id ->
            if (id != START && id != END && id !in connectedNodes) {
                errors.add("[$name] -- Isolated node: $id")
            }
        }

        return if (errors.isEmpty()) {
            ValidationResult.Valid
        } else {
            ValidationResult.Invalid(errors)
        }
    }
}

sealed class ValidationResult {
    object Valid : ValidationResult()
    data class Invalid(val errors: List<String>) : ValidationResult()
}

