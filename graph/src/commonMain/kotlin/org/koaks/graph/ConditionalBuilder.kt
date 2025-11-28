package org.koaks.graph

class ConditionalEdgeBuilder(
    private val from: String,
    private val router: suspend (GraphContext) -> String
) {
    private val mappings = linkedMapOf<String, String>()
    private var useImplicitRouting: Boolean = true

    infix fun String.to(target: String) {
        mappings[this] = target
    }

    fun strict() {
        useImplicitRouting = false
    }

    internal fun build(): Edge {
        return ConditionalEdge(
            from = from,
            router = router,
            cases = mappings.toMap(),
            useImplicitRouting = useImplicitRouting
        )
    }
}
