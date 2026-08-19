package org.koaks.json

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** The single JSON configuration used by all Koaks interop hosts. */
internal val wireJson = Json {
    ignoreUnknownKeys = false
    explicitNulls = false
    encodeDefaults = true
    classDiscriminator = "type"
}

internal fun JsonObject.requiredString(name: String): String =
    this[name]?.jsonPrimitive?.contentOrNull ?: error("'$name' is required")

internal fun JsonObject.optionalString(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull
internal fun JsonObject.optionalInt(name: String): Int? = this[name]?.jsonPrimitive?.intOrNull
internal fun JsonObject.optionalLong(name: String): Long? = this[name]?.jsonPrimitive?.longOrNull
internal fun JsonObject.optionalBoolean(name: String): Boolean? = this[name]?.jsonPrimitive?.booleanOrNull
internal fun JsonObject.optionalObject(name: String): JsonObject? = this[name] as? JsonObject
internal fun JsonObject.optionalArray(name: String): JsonArray? = this[name] as? JsonArray

internal fun JsonObject.requiredObject(name: String): JsonObject =
    optionalObject(name) ?: error("'$name' must be an object")

internal fun JsonObject.requiredArray(name: String): JsonArray =
    optionalArray(name) ?: error("'$name' must be an array")

internal fun String.decodeWireObject(): JsonObject =
    if (isBlank()) JsonObject(emptyMap()) else wireJson.parseToJsonElement(this).let {
        it as? JsonObject ?: error("wire JSON value must be an object")
    }

