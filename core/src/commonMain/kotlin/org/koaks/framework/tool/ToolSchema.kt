package org.koaks.framework.tool

import kotlinx.serialization.json.JsonObject

/**
 * A tool description handed to the model: name, description, and a JSON Schema
 * for the input object. The schema is produced from the tool's input
 * [kotlinx.serialization.KSerializer] by the schema generator — never hand-written.
 */
data class ToolSchema(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)
