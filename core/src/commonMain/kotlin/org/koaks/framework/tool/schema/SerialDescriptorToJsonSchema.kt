package org.koaks.framework.tool.schema

import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * Minimal but real `SerialDescriptor → JSON Schema` generator, running purely in
 * commonMain (no JVM reflection).
 *
 * Slice scope (design §8.1) — covers the high-frequency tool-input shapes:
 *  - flat data class (object structure)
 *  - primitives: String / Int / Long / Boolean / Double / Float (+ Short/Byte/Char)
 *  - enum → `{ "type": "string", "enum": [...] }`
 *  - nullable → field omitted from `required`
 *  - `List<primitive>` → `{ "type": "array", "items": {...} }`
 *  - `@SerialName` → honored via the descriptor's element names / serialName
 *
 * Deliberately NOT covered yet (deferred to Phase 2): nested objects, sealed /
 * polymorphic, generics, recursion, Map, contextual. These throw or degrade
 * rather than silently producing a wrong schema.
 */
object SerialDescriptorToJsonSchema {

    /**
     * Builds the JSON Schema for a tool input type described by [descriptor].
     * The top level is expected to be an object (data class) or the
     * empty-object case for tools with no input.
     */
    fun generate(descriptor: SerialDescriptor): JsonObject {
        return when (descriptor.kind) {
            StructureKind.CLASS, StructureKind.OBJECT -> objectSchema(descriptor)
            else -> error(
                "tool input must be a data class / object, got kind ${descriptor.kind} " +
                    "for '${descriptor.serialName}'"
            )
        }
    }

    private fun objectSchema(descriptor: SerialDescriptor): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object"))

        val properties = buildJsonObject {
            for (i in 0 until descriptor.elementsCount) {
                val name = descriptor.getElementName(i)
                put(name, elementSchema(descriptor.getElementDescriptor(i)))
            }
        }
        put("properties", properties)

        // required = non-nullable elements without a default.
        val required = buildJsonArray {
            for (i in 0 until descriptor.elementsCount) {
                val elem = descriptor.getElementDescriptor(i)
                val optional = descriptor.isElementOptional(i)
                if (!elem.isNullable && !optional) {
                    add(JsonPrimitive(descriptor.getElementName(i)))
                }
            }
        }
        if (required.isNotEmpty()) put("required", required)
    }

    private fun elementSchema(descriptor: SerialDescriptor): JsonObject {
        // Enum is reported as SerialKind.ENUM regardless of nullability.
        if (descriptor.kind == SerialKind.ENUM) return enumSchema(descriptor)

        return when (val kind = descriptor.kind) {
            is PrimitiveKind -> primitiveSchema(kind)
            StructureKind.LIST -> listSchema(descriptor)
            StructureKind.CLASS, StructureKind.OBJECT -> error(
                "nested object '${descriptor.serialName}' is out of slice scope; " +
                    "deferred to Phase 2"
            )
            StructureKind.MAP -> error("Map is out of slice scope; deferred to Phase 2")
            else -> error("unsupported descriptor kind $kind for '${descriptor.serialName}'")
        }
    }

    private fun primitiveSchema(kind: PrimitiveKind): JsonObject = buildJsonObject {
        val type = when (kind) {
            PrimitiveKind.STRING, PrimitiveKind.CHAR -> "string"
            PrimitiveKind.BOOLEAN -> "boolean"
            PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG -> "integer"
            PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> "number"
        }
        put("type", JsonPrimitive(type))
    }

    private fun enumSchema(descriptor: SerialDescriptor): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("string"))
        put("enum", buildJsonArray {
            for (i in 0 until descriptor.elementsCount) {
                add(JsonPrimitive(descriptor.getElementName(i)))
            }
        })
    }

    private fun listSchema(descriptor: SerialDescriptor): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("array"))
        val itemDescriptor = descriptor.getElementDescriptor(0)
        val itemKind = itemDescriptor.kind
        val items = when {
            itemKind == SerialKind.ENUM -> enumSchema(itemDescriptor)
            itemKind is PrimitiveKind -> primitiveSchema(itemKind)
            else -> error(
                "only List<primitive>/List<enum> is in slice scope; " +
                    "List item kind $itemKind deferred to Phase 2"
            )
        }
        put("items", items)
    }
}
