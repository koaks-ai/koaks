package org.koaks.framework.toolcall

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.descriptors.*
import org.koaks.framework.annotation.Description
import org.koaks.framework.toolcall.toolinterface.Tool
import org.koaks.framework.utils.JsonUtil

private val logger = KotlinLogging.logger {}

fun <T> Tool<T>.toDefinition(): ToolDefinition {
    val params = serializer.descriptor.toToolParameters()
    return ToolDefinition(
        type = "function",
        function = ToolDefinition.Function(
            name = name,
            description = description,
            parameters = params
        )
    ).apply {
        group = this@toDefinition.group ?: "default"
        toolType = ToolType.INTERFACE
        toolImplementation = this@toDefinition
    }
}

fun SerialDescriptor.toToolParameters(): ToolDefinition.ToolParameters {
    return if (kind == StructureKind.CLASS || kind == StructureKind.OBJECT) {
        val props = (0 until elementsCount).associate { index ->
            val propName = getElementName(index)
            val propType = getElementDescriptor(index)

            val desc = getElementAnnotations(index)
                .filterIsInstance<Description>()
                .firstOrNull()?.text ?: run {
                logger.warn { "No description found for property $propName" }
                "no description"
            }

            propName to ToolDefinition.Property(
                type = propType.toJsonSchemaType(),
                description = desc
            )
        }
        val required = (0 until elementsCount)
            .filter { !isElementOptional(it) }
            .map { getElementName(it) }

        ToolDefinition.ToolParameters(
            type = "object",
            properties = props,
            required = required
        )
    } else {
        // wrap non-object inputs (such as a single String/Int) into a value field
        ToolDefinition.ToolParameters(
            type = "object",
            properties = mapOf(
                "value" to ToolDefinition.Property(
                    type = toJsonSchemaType(),
                    description = ""
                )
            ),
            required = listOf("value")
        )
    }
}

fun SerialDescriptor.toJsonSchemaType(): String {
    return when (kind) {
        is PrimitiveKind.STRING -> "string"
        is PrimitiveKind.INT -> "integer"
        is PrimitiveKind.BOOLEAN -> "boolean"
        is PrimitiveKind.LONG -> "integer"
        is PrimitiveKind.FLOAT, is PrimitiveKind.DOUBLE -> "number"
        else -> "object"
    }
}

fun ToolDefinition.toJson() = JsonUtil.toJson(this)

fun String.toToolDefinition() = JsonUtil.fromJson<ToolDefinition>(this)
