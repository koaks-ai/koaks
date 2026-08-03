package org.koaks.framework.annotation

import kotlinx.serialization.serializer
import org.koaks.framework.annotation.Tool as ToolAnnotation
import org.koaks.framework.tool.InlineTool
import org.koaks.framework.tool.Tool

/**
 * Builds a [Tool] from an input type [In] annotated with [Tool] (the annotation),
 * reading `name`/`description` from the annotation and the schema from [In]'s
 * serializer. Execution is the supplied [execute] lambda — no reflection at call time.
 *
 * ```kotlin
 * @Serializable
 * @Tool(name = "weather", description = "Get weather")
 * data class WeatherInput(@Param(name = "city", description = "city name") val city: String)
 *
 * tools { tool(annotatedTool<WeatherInput> { fetchWeather(it.city) }) }
 * ```
 */
inline fun <reified In> annotatedTool(
    returnDirectly: Boolean = false,
    hasSideEffects: Boolean = false,
    noinline execute: suspend (In) -> String,
): Tool<In> {
    // Read the annotation via Java reflection (no kotlin-reflect dependency needed).
    val meta = requireNotNull(In::class.java.getAnnotation(ToolAnnotation::class.java)) {
        "${In::class.java.simpleName} must be annotated with @org.koaks.framework.annotation.Tool"
    }
    return InlineTool(
        name = meta.name.ifBlank { In::class.java.simpleName },
        description = meta.value.ifBlank { meta.description }.also {
            require(it.isNotBlank()) { "@Tool description on ${In::class.java.name} must not be blank" }
        },
        inputSerializer = serializer<In>(),
        returnDirectly = returnDirectly,
        hasSideEffects = hasSideEffects,
        block = execute,
    )
}
