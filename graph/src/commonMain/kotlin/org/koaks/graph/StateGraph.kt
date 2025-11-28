package org.koaks.graph

import kotlin.collections.mutableListOf

class StateGraph(
    val name: String,
) {
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

    fun getNode(id: String): Node = nodes[id]
        ?: error("Node not found: $id")

    fun getAllNodes(): List<Node> = nodes.values.toList()
    fun getAllEdges(): List<Edge> = edges.toList()

    fun validate(): ValidationResult {
        val errors = mutableListOf<String>()

        // 获取一个边所有的目标节点
        fun getEdgeTargets(edge: Edge): List<String> {
            return when (edge) {
                is DirectEdge -> listOf(edge.to)
                is ConditionalEdge -> edge.cases.values.toList()
            }
        }

        // 1. 检查边引用的节点是否存在
        edges.forEach { edge ->
            if (edge.from !in nodes) {
                errors.add("[$name] -- Edge from non-existent node: ${edge.from}")
            }

            getEdgeTargets(edge).forEach { targetId ->
                if (targetId !in nodes) {
                    errors.add("[$name] -- Edge to non-existent node: $targetId")
                }
            }
        }

        // 2. 检查 START 节点必须有出边
        if (edges.none { it.from == START }) {
            errors.add("[$name] -- START node has no outgoing edges")
        }

        // 3. 检查孤立节点 (计算 connectivity)
        val connectedNodes = mutableSetOf<String>()
        edges.forEach { edge ->
            connectedNodes.add(edge.from)
            connectedNodes.addAll(getEdgeTargets(edge))
        }

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

