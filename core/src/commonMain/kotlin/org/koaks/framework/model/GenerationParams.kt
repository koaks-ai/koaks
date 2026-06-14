package org.koaks.framework.model

/**
 * Provider-agnostic sampling / generation parameters. Providers map the subset
 * they support into their own wire format; unknown fields are ignored.
 */
data class GenerationParams(
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val topP: Double? = null,
    val stop: List<String>? = null,
    val presencePenalty: Double? = null,
    val frequencyPenalty: Double? = null,
)
