@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package org.koaks.framework.tool.schema

import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * `SerialDescriptor → JSON Schema` generator, running purely in commonMain (no JVM
 * reflection).
 *
 * Full coverage:
 *  - flat & nested data classes (objects)
 *  - primitives: String / Int / Long / Boolean / Double / Float (+ Short/Byte/Char)
 *  - enum → `{ "type": "string", "enum": [...] }`
 *  - nullable → field omitted from `required` (and unioned with the base type)
 *  - `List<T>` / arrays → `{ "type": "array", "items": <T> }`
 *  - `Map<K, V>` → `{ "type": "object", "additionalProperties": <V> }`
 *  - sealed / polymorphic → `oneOf` of subtype schemas + a discriminator property
 *  - recursion → handled via `$ref` / `$defs`; a self-referential type emits a
 *    `$ref` to its own definition rather than recursing forever
 *  - `@SerialName` → honored via element names / serialName
 *
 * Named composite types (objects, enums, sealed hierarchies) are hoisted into
 * `$defs` and referenced by `$ref`, which both deduplicates and breaks recursion.
 */
object SerialDescriptorToJsonSchema {

    private const val DEFS = "\$defs"
    private const val REF = "\$ref"

    /**
     * Builds the JSON Schema for a tool input type. The top level is expected to be
     * an object (data class), an empty object (no-input tools), or a sealed type.
     * When any named composite types are reached they are emitted under `$defs` and
     * referenced; the top-level schema is inlined for ergonomics.
     */
    fun generate(descriptor: SerialDescriptor): JsonObject {
        val ctx = Ctx()
        val root = ctx.schemaFor(descriptor, inlineTopLevel = true)
        if (ctx.defs.isEmpty()) return root
        return buildJsonObject {
            root.forEach { (k, v) -> put(k, v) }
            put(DEFS, buildJsonObject { ctx.defs.forEach { (k, v) -> put(k, v) } })
        }
    }

    /** Mutable walk state: accumulates `$defs` and tracks in-progress names for recursion. */
    private class Ctx {
        val defs = LinkedHashMap<String, JsonObject>()
        private val inProgress = HashSet<String>()

        /**
         * Returns the schema for [descriptor]. Composite named types are registered
         * into [defs] and a `$ref` is returned, except when [inlineTopLevel] is set
         * (the very first call), where the body is returned directly.
         */
        fun schemaFor(descriptor: SerialDescriptor, inlineTopLevel: Boolean = false): JsonObject {
            // Enums are small leaves — always inlined, never hoisted into $defs.
            if (descriptor.kind == SerialKind.ENUM) return enumSchema(descriptor)
            return when (val kind = descriptor.kind) {
                is PrimitiveKind -> primitiveSchema(kind)
                StructureKind.LIST -> listSchema(descriptor)
                StructureKind.MAP -> mapSchema(descriptor)
                StructureKind.CLASS, StructureKind.OBJECT ->
                    if (inlineTopLevel) objectSchema(descriptor) else refForNamed(descriptor) { objectSchema(it) }
                PolymorphicKind.SEALED, PolymorphicKind.OPEN ->
                    if (inlineTopLevel) sealedSchema(descriptor) else refForNamed(descriptor) { sealedSchema(it) }
                else -> error("unsupported descriptor kind $kind for '${descriptor.serialName}'")
            }
        }

        /** Element schema with nullability folded in (used inside object properties / containers). */
        fun elementSchema(descriptor: SerialDescriptor): JsonObject {
            val base = schemaFor(descriptor)
            // A nullable primitive/enum becomes a {type: [..., "null"]} or oneOf-with-null union.
            return if (descriptor.isNullable) withNull(base) else base
        }

        private fun refForNamed(descriptor: SerialDescriptor, build: (SerialDescriptor) -> JsonObject): JsonObject {
            val name = defName(descriptor)
            if (name !in defs && name !in inProgress) {
                inProgress += name
                defs[name] = build(descriptor)   // building may recurse and add more defs
                inProgress -= name
            }
            return buildJsonObject { put(REF, JsonPrimitive("#/$DEFS/$name")) }
        }

        private fun objectSchema(descriptor: SerialDescriptor): JsonObject = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", buildJsonObject {
                for (i in 0 until descriptor.elementsCount) {
                    put(descriptor.getElementName(i), elementSchema(descriptor.getElementDescriptor(i)))
                }
            })
            val required = buildJsonArray {
                for (i in 0 until descriptor.elementsCount) {
                    val elem = descriptor.getElementDescriptor(i)
                    if (!elem.isNullable && !descriptor.isElementOptional(i)) {
                        add(JsonPrimitive(descriptor.getElementName(i)))
                    }
                }
            }
            if (required.isNotEmpty()) put("required", required)
        }

        /**
         * Sealed/polymorphic: kotlinx encodes these as an object with elements
         * `[0]=type: String` (discriminator) and `[1]=value: <actual>` where the
         * value element is itself a `SEALED` descriptor whose elements are the
         * registered subtypes. We emit `oneOf` of the subtype object schemas, each
         * augmented with a `const` discriminator carrying the subtype serialName.
         */
        private fun sealedSchema(descriptor: SerialDescriptor): JsonObject {
            val valueDescriptor = descriptor.getElementDescriptor(1)
            return buildJsonObject {
                put("oneOf", buildJsonArray {
                    for (i in 0 until valueDescriptor.elementsCount) {
                        val sub = valueDescriptor.getElementDescriptor(i)
                        val discriminator = valueDescriptor.getElementName(i)
                        add(subtypeSchema(sub, discriminator))
                    }
                })
            }
        }

        /** A subtype object schema with a `type` discriminator const folded in. */
        private fun subtypeSchema(descriptor: SerialDescriptor, discriminator: String): JsonObject {
            val body = objectSchema(descriptor)
            val props = (body["properties"] as? JsonObject) ?: JsonObject(emptyMap())
            return buildJsonObject {
                put("type", JsonPrimitive("object"))
                put("properties", buildJsonObject {
                    put("type", buildJsonObject {
                        put("const", JsonPrimitive(discriminator))
                    })
                    props.forEach { (k, v) -> put(k, v) }
                })
                val required = buildJsonArray {
                    add(JsonPrimitive("type"))
                    (body["required"] as? kotlinx.serialization.json.JsonArray)?.forEach { add(it) }
                }
                put("required", required)
            }
        }

        private fun listSchema(descriptor: SerialDescriptor): JsonObject = buildJsonObject {
            put("type", JsonPrimitive("array"))
            put("items", elementSchema(descriptor.getElementDescriptor(0)))
        }

        /**
         * Map → JSON object with `additionalProperties` = value schema. JSON object
         * keys are always strings, so the key descriptor (element 0) is ignored; the
         * value descriptor is element 1.
         */
        private fun mapSchema(descriptor: SerialDescriptor): JsonObject = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("additionalProperties", elementSchema(descriptor.getElementDescriptor(1)))
        }
    }

    private fun enumSchema(descriptor: SerialDescriptor): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("string"))
        put("enum", buildJsonArray {
            for (i in 0 until descriptor.elementsCount) add(JsonPrimitive(descriptor.getElementName(i)))
        })
    }

    private fun primitiveSchema(kind: PrimitiveKind): JsonObject = buildJsonObject {
        put("type", JsonPrimitive(jsonType(kind)))
    }

    private fun jsonType(kind: PrimitiveKind): String = when (kind) {
        PrimitiveKind.STRING, PrimitiveKind.CHAR -> "string"
        PrimitiveKind.BOOLEAN -> "boolean"
        PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG -> "integer"
        PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> "number"
    }

    /** Unions a schema with `null`. For `{type: X}` produces `{type: [X, "null"]}`; otherwise wraps in oneOf. */
    private fun withNull(base: JsonObject): JsonObject {
        val type = base["type"]
        if (type is JsonPrimitive && base.keys == setOf("type")) {
            return buildJsonObject {
                put("type", buildJsonArray { add(type); add(JsonPrimitive("null")) })
            }
        }
        return buildJsonObject {
            put("oneOf", buildJsonArray { add(base); add(buildJsonObject { put("type", JsonPrimitive("null")) }) })
        }
    }

    /** A stable `$defs` key for a named type — last path segment of the serialName. */
    private fun defName(descriptor: SerialDescriptor): String =
        descriptor.serialName.substringAfterLast('.').substringBefore('?').ifEmpty { descriptor.serialName }
}
