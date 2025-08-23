package org.koaks.workflow.node

interface Node<T> {

    val id: String

    val name: String

    val type: NodeType

    val nextNodes: List<Node<T>>

    fun execute(input: T): T

}