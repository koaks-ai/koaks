package org.koaks.framework.model

/**
 * Declares what a model can do. Per design §3.2, capabilities are declared by the
 * developer in the DSL; the framework maintains no "model → capability" table.
 * The runtime only reads these to adapt (e.g. fall back to prompt-based JSON when
 * [jsonMode] is false), never to guess.
 *
 * Defaults are deliberately permissive for the common cases (streaming, tools) and
 * conservative for the model-specific ones (vision, jsonMode).
 */
data class ModelCapabilities(
    val streaming: Boolean = true,
    val tools: Boolean = true,
    val parallelToolCalls: Boolean = true,
    val vision: Boolean = false,
    val jsonMode: Boolean = false,
)
