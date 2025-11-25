package org.koaks.graph

class ConditionalEdgeBuilder(
    private val from: String,
    private val router: suspend (GraphContext) -> String
) {
    private val mappings = mutableMapOf<String, String>()

    infix fun String.to(target: String) {
        mappings[this] = target
    }

    internal fun build(): List<ConditionalEdge> {
        return mappings.map { (key, target) ->
            ConditionalEdge(
                from = from,
                to = target,
                condition = { ctx -> router(ctx) == key }
            )
        }
    }
}
