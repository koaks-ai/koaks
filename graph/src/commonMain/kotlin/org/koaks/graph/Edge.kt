package org.koaks.graph

sealed class Edge {
    abstract val from: String
    abstract val to: String
}

data class DirectEdge(
    override val from: String,
    override val to: String
) : Edge()

data class ConditionalEdge(
    override val from: String,
    val router: suspend (GraphContext) -> String,
    val cases: Map<String, String>
) : Edge() {
    override val to: String = "__CONDITIONAL__"
}