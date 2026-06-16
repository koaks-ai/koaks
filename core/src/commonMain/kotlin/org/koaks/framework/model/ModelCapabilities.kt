package org.koaks.framework.model

/**
 * Declares the model-specific capabilities a developer may need to adapt to. The
 * capabilities are declared by the developer in the DSL; the framework maintains no
 * "model → capability" table. The runtime only reads these to adapt (e.g. fall back
 * to prompt-based JSON when [jsonMode] is false), never to guess.
 *
 * Streaming and tool calling are no longer declared here — every contemporary model
 * supports them, so they are plain request parameters ([ChatRequest.stream] / the
 * tool schemas) rather than capability gates.
 */
data class ModelCapabilities(
    val parallelToolCalls: Boolean = true,
    val vision: Boolean = false,
    val jsonMode: Boolean = false,
)
