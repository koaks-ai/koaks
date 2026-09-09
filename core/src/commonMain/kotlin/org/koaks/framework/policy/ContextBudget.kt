package org.koaks.framework.policy

import org.koaks.framework.model.ModelRequest

/** Counts the complete input request, including instructions, tools and output schema. */
fun interface InputTokenCounter {
    suspend fun count(request: ModelRequest): Int
}

data class ContextBudget(val maxInputTokens: Int, val counter: InputTokenCounter) {
    init { require(maxInputTokens > 0) { "maxInputTokens must be positive" } }
}
