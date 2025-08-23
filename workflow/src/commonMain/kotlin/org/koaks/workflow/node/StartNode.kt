package org.koaks.workflow.node

class StartNode<T>(
    override val id: String,
    override val name: String,
    val input: T,
) : Node<T> {

    override val type = NodeType.START
    override val nextNodes = mutableListOf<Node<T>>()
    override fun execute(input: T): T {
        TODO("Not yet implemented")
    }

}