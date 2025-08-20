package org.koaks.framework.toolcall

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.reflect.KFunction

@Serializable
data class ToolDefinition(
    val type: String = "function",
    val function: Function,
) {
    @Transient
    lateinit var realFunction: KFunction<*>

    @Transient
    val toolname: String = function.name

    @Transient
    var group: String = "default"

    @Serializable
    data class Function(
        val name: String,
        val description: String,
        val parameters: ToolParameters? = null
    )

    @Serializable
    data class ToolParameters(
        val type: String = "object",
        val properties: Map<String, Property> = emptyMap(),
        val required: Array<String> = emptyArray()
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as ToolParameters

            if (type != other.type) return false
            if (properties != other.properties) return false
            if (!required.contentEquals(other.required)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = type.hashCode()
            result = 31 * result + properties.hashCode()
            result = 31 * result + required.contentHashCode()
            return result
        }
    }

    @Serializable
    data class Property(val type: String, val description: String)
}