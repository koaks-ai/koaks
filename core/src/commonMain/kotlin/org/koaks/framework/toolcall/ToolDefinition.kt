package org.koaks.framework.toolcall

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import org.koaks.framework.platform.PlatformUtils
import org.koaks.framework.platform.PlatformType
import org.koaks.framework.toolcall.toolinterface.Tool
import kotlin.reflect.KFunction

@Serializable
data class ToolDefinition(
    val type: String = "function",
    val function: Function,
) {

    @Transient
    val platform: PlatformType = PlatformUtils.platformType()

    @Transient
    var toolType: ToolType = ToolType.ANNOTATION

    /** only jvm */
    @Transient
    var realFunction: KFunction<*>? = null

    /**
     * only implemented using the interface need
     */
    @Transient
    var toolImplementation: Tool<*>? = null

    @Transient
    val toolName: String = function.name

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

        override fun hashCode(): Int {
            var result = type.hashCode()
            result = 31 * result + properties.hashCode()
            result = 31 * result + required.contentHashCode()
            return result
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || this::class != other::class) return false

            other as ToolParameters

            if (type != other.type) return false
            if (properties != other.properties) return false
            if (!required.contentEquals(other.required)) return false

            return true
        }
    }

    @Serializable
    data class Property(val type: String, val description: String)

}
