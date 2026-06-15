package org.koaks.framework.loop

import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.koaks.framework.tool.schema.SerialDescriptorToJsonSchema
import org.koaks.framework.utils.json.JsonExtractor

/**
 * Structured-output run (design §5.2): returns a typed [T] decoded from the model's
 * final answer. The schema is derived from [T]'s serializer; the loop runs tools
 * freely and only the final step is format-constrained. The response is tolerantly
 * parsed (fences stripped, first JSON object extracted) before decoding.
 *
 * ```kotlin
 * @Serializable data class CityWeather(val city: String, val tempC: Int)
 * val w: CityWeather = agent.run<CityWeather>("weather in NYC?")
 * ```
 */
private val structuredJson = Json { ignoreUnknownKeys = true; isLenient = true }

suspend inline fun <reified T> Agent.run(input: String): T {
    val serializer = serializer<T>()
    val schema = SerialDescriptorToJsonSchema.generate(serializer.descriptor)
    val spec = OutputSpec(schema, serializer.descriptor.serialName.substringAfterLast('.'))
    val result = runStructured(input, spec)
    val json = JsonExtractor.extract(result.text)
    return decodeStructured(serializer, json)
}

/** Decodes the extracted JSON; isolated from the inline fun so [structuredJson] stays private. */
@PublishedApi
internal fun <T> decodeStructured(serializer: kotlinx.serialization.KSerializer<T>, json: String): T =
    structuredJson.decodeFromString(serializer, json)
