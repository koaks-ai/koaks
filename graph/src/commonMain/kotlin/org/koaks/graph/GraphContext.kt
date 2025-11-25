package org.koaks.graph

class GraphContext internal constructor(
    initialState: Map<String, Any> = emptyMap()
) {
    private val state = initialState.toMutableMap()

    private val history = mutableListOf(StateGraph.START)

    private var currentNode: String = StateGraph.START

    private var iteration: Int = 0

    internal fun iterationIncrease() = iteration++

    internal fun currentNode(): String = currentNode

    fun iteration(): Int = iteration

    fun isFinished(): Boolean = currentNode == StateGraph.END

    @Suppress("UNCHECKED_CAST")
    fun <T> getValue(key: String): T? = state[key] as? T

    fun setValue(key: String, value: Any) {
        state[key] = value
    }

    fun setAllValues(map: Map<String, Any>) {
        state.putAll(map)
    }

    fun removeValue(key: String): Any? = state.remove(key)

    fun clear() = state.clear()

    fun hasValue(key: String): Boolean = contains(key)

    operator fun contains(key: String): Boolean = key in state

    fun snapshot(): Map<String, Any> = mapOf(
        "state" to state.toMap(),
        "history" to history.toList(),
    )

    internal fun moveTo(nodeId: String) {
        currentNode = nodeId
        iterationIncrease()
        history.add(nodeId)
    }

}
