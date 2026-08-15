package org.koaks.framework.model

import kotlinx.serialization.Serializable

/**
 * Token usage for a single model call or accumulated across a run.
 *
 * [cachedInputTokens] and [reasoningOutputTokens] are billed quantities reported
 * by both OpenAI Responses and Anthropic; unknown providers leave them at 0.
 */
@Serializable
data class Usage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0,
    val cachedInputTokens: Int = 0,
    val reasoningOutputTokens: Int = 0,
) {
    operator fun plus(other: Usage): Usage = Usage(
        promptTokens = promptTokens + other.promptTokens,
        completionTokens = completionTokens + other.completionTokens,
        totalTokens = totalTokens + other.totalTokens,
        cachedInputTokens = cachedInputTokens + other.cachedInputTokens,
        reasoningOutputTokens = reasoningOutputTokens + other.reasoningOutputTokens,
    )

    companion object {
        val ZERO = Usage()
    }
}
