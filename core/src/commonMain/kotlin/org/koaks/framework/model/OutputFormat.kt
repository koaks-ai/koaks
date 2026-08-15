package org.koaks.framework.model

import kotlinx.serialization.json.JsonObject

sealed interface OutputFormat {
    data object Text : OutputFormat
    data object JsonObject : OutputFormat
    data class JsonSchema(
        val name: String,
        val schema: kotlinx.serialization.json.JsonObject,
        val strict: Boolean = false,
    ) : OutputFormat
}
