package org.koaks.graph

class Node(
    val id: String,
    val type: NodeType = NodeType.NORMAL,
    val action: suspend (GraphContext) -> Unit = {}
) {

    override fun toString(): String {
        return "Node(id='$id', type=$type)"
    }

}