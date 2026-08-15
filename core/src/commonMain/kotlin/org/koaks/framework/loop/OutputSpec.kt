package org.koaks.framework.loop

import kotlinx.serialization.json.JsonObject

/**
 * Describes how the agent should produce structured output for `run<T>`.
 *
 * Strategy is capabilities-driven and decided at run time:
 *  - if the model supports native JSON Schema / JSON object, the final request uses [org.koaks.framework.model.OutputFormat];
 *  - otherwise the JSON [schema] is injected as a user-message prompt constraint.
 *
 * "Format only on the last step": the tool loop runs with NO json constraint (so the
 * model can call tools freely); only once the loop would finish is a single
 * format-constrained request issued. See [AgentRunner].
 */
data class OutputSpec(
    val schema: JsonObject,
    val schemaName: String,
)
