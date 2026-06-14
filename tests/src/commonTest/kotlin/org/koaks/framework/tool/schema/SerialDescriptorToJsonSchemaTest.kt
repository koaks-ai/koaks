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
}
