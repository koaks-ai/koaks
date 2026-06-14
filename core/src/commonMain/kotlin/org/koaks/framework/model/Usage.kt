package org.koaks.framework.model

import kotlinx.serialization.Serializable

/**
 * Token usage for a single model call or accumulated across a run.
 */
@Serializable
data class Usage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0,
) {
    operator fun plus(other: Usage): Usage = Usage(
        promptTokens = promptTokens + other.promptTokens,
        completionTokens = completionTokens + other.completionTokens,
        totalTokens = totalTokens + other.totalTokens,
    )

    companion object {
        val ZERO = Usage()
    }
}
