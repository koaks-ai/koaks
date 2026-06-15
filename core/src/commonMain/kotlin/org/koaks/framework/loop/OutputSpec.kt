package org.koaks.framework.loop

import kotlinx.serialization.json.JsonObject

/**
 * Describes how the agent should produce structured output for `run<T>` (design §5.2).
 *
 * Strategy is capabilities-driven and decided at run time:
 *  - if the model supports native JSON mode, the final request sets `jsonMode = true`;
 *  - otherwise the JSON [schema] is injected into the instructions as a prompt
 *    constraint, and the response is tolerantly parsed (fences stripped, first JSON
 *    object extracted).
 *
 * "Format only on the last step": the tool loop runs with NO json constraint (so the
 * model can call tools freely); only once the loop would finish is a single
 * format-constrained request issued. See [AgentRunner].
 */
data class OutputSpec(
    val schema: JsonObject,
    val schemaName: String,
)
