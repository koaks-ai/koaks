package org.koaks.framework.tool.schema

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SerialDescriptorToJsonSchemaTest {

    @Serializable
    enum class Unit { CELSIUS, FAHRENHEIT }

    @Serializable
    data class Query(
        val city: String,
        @SerialName("max_results") val maxResults: Int,
        val verbose: Boolean = false,
        val note: String? = null,
        val unit: Unit,
        val tags: List<String>,
    )

    private fun schema() = SerialDescriptorToJsonSchema.generate(serializer<Query>().descriptor)

    @Test
    fun produces_object_with_properties() {
        val s = schema()
        assertEquals("object", (s["type"] as JsonPrimitive).content)
        val props = s["properties"] as JsonObject
        assertTrue("city" in props)
        assertTrue("max_results" in props, "@SerialName must be honored")
    }

    @Test
    fun maps_primitive_types() {
        val props = schema()["properties"] as JsonObject
        assertEquals("string", typeOf(props["city"]!!))
        assertEquals("integer", typeOf(props["max_results"]!!))
        assertEquals("boolean", typeOf(props["verbose"]!!))
    }

    @Test
    fun enum_becomes_string_with_enum_values() {
        val props = schema()["properties"] as JsonObject
        val unit = props["unit"] as JsonObject
        assertEquals("string", (unit["type"] as JsonPrimitive).content)
        val values = (unit["enum"] as JsonArray).map { (it as JsonPrimitive).content }
        assertEquals(listOf("CELSIUS", "FAHRENHEIT"), values)
    }

    @Test
    fun list_of_primitive_becomes_array() {
        val props = schema()["properties"] as JsonObject
        val tags = props["tags"] as JsonObject
        assertEquals("array", (tags["type"] as JsonPrimitive).content)
        assertEquals("string", typeOf(tags["items"]!!))
    }

    @Test
    fun required_excludes_nullable_and_optional() {
        val required = (schema()["required"] as JsonArray).map { (it as JsonPrimitive).content }
        assertTrue("city" in required)
        assertTrue("max_results" in required)
        assertTrue("unit" in required)
        assertTrue("tags" in required)
        assertTrue("note" !in required, "nullable field must not be required")
        assertTrue("verbose" !in required, "field with default must not be required")
    }

    private fun typeOf(el: kotlinx.serialization.json.JsonElement): String =
        ((el as JsonObject)["type"] as JsonPrimitive).content

    // ---- Phase 2: nested objects, Map, sealed, recursion, nullable unions ----

    @Serializable
    data class Address(val street: String, val zip: String)

    @Serializable
    data class Person(val name: String, val address: Address, val tags: Map<String, Int>)

    @Test
    fun nested_object_is_hoisted_into_defs_and_referenced() {
        val s = SerialDescriptorToJsonSchema.generate(serializer<Person>().descriptor)
        val props = s["properties"] as JsonObject
        // The nested Address is referenced, not inlined.
        val addr = props["address"] as JsonObject
        val ref = (addr["\$ref"] as JsonPrimitive).content
        assertTrue(ref.startsWith("#/\$defs/"), "nested object must be a \$ref, was $ref")
        val defs = s["\$defs"] as JsonObject
        val addressDef = defs["Address"] as JsonObject
        assertEquals("object", (addressDef["type"] as JsonPrimitive).content)
        assertTrue("street" in (addressDef["properties"] as JsonObject))
    }

    @Test
    fun map_becomes_object_with_additional_properties() {
        val s = SerialDescriptorToJsonSchema.generate(serializer<Person>().descriptor)
        val props = s["properties"] as JsonObject
        val tags = props["tags"] as JsonObject
        assertEquals("object", (tags["type"] as JsonPrimitive).content)
        assertEquals("integer", typeOf(tags["additionalProperties"]!!))
    }

    @Serializable
    sealed interface Shape {
        @Serializable @SerialName("circle") data class Circle(val radius: Double) : Shape
        @Serializable @SerialName("rect") data class Rect(val w: Double, val h: Double) : Shape
    }

    @Test
    fun sealed_becomes_oneOf_with_discriminator_const() {
        val s = SerialDescriptorToJsonSchema.generate(serializer<Shape>().descriptor)
        val oneOf = s["oneOf"] as JsonArray
        assertEquals(2, oneOf.size)
        val discriminators = oneOf.map { variant ->
            val props = (variant as JsonObject)["properties"] as JsonObject
            ((props["type"] as JsonObject)["const"] as JsonPrimitive).content
        }
        assertTrue("circle" in discriminators)
        assertTrue("rect" in discriminators)
    }

    @Serializable
    data class TreeNode(val value: Int, val children: List<TreeNode> = emptyList())

    @Test
    fun recursive_type_terminates_via_ref() {
        val s = SerialDescriptorToJsonSchema.generate(serializer<TreeNode>().descriptor)
        // children: array whose items is a $ref back to TreeNode.
        val props = s["properties"] as JsonObject
        val children = props["children"] as JsonObject
        assertEquals("array", (children["type"] as JsonPrimitive).content)
        val items = children["items"] as JsonObject
        assertTrue((items["\$ref"] as JsonPrimitive).content.endsWith("/TreeNode"))
        val defs = s["\$defs"] as JsonObject
        assertTrue("TreeNode" in defs)
    }

    @Serializable
    data class WithNullableNested(val maybe: Address?)

    @Test
    fun nullable_nested_object_unions_with_null() {
        val s = SerialDescriptorToJsonSchema.generate(serializer<WithNullableNested>().descriptor)
        val props = s["properties"] as JsonObject
        val maybe = props["maybe"] as JsonObject
        // Nullable composite → oneOf [ <ref>, {type: null} ].
        val oneOf = maybe["oneOf"] as JsonArray
        assertTrue(oneOf.any { (it as JsonObject)["\$ref"] != null })
        assertTrue(oneOf.any { (it as JsonObject)["type"]?.let { t -> (t as JsonPrimitive).content } == "null" })
    }
}
