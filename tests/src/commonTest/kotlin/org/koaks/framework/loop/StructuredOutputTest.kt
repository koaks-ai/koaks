package org.koaks.framework.loop

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import org.koaks.framework.model.ModelCapabilities
import org.koaks.framework.model.ModelEvent
import org.koaks.framework.model.Usage
import org.koaks.framework.utils.json.JsonExtractor
import kotlin.test.Test
import kotlin.test.assertEquals

class StructuredOutputTest {

    @Serializable
    data class CityWeather(val city: String, val tempC: Int)

    @Test
    fun extracts_json_from_fenced_response() {
        val raw = "Sure! Here is the result:\n```json\n{\"city\":\"NYC\",\"tempC\":21}\n```\nHope that helps."
        val json = JsonExtractor.extract(raw)
        assertEquals("{\"city\":\"NYC\",\"tempC\":21}", json)
    }

    @Test
    fun extracts_json_with_nested_braces() {
        val raw = "{\"a\":{\"b\":1},\"c\":\"}\"}"  // brace inside a string must not fool the matcher
        assertEquals(raw, JsonExtractor.extract(raw))
    }

    @Test
    fun run_typed_decodes_structured_output_via_prompt_fallback() = runTest {
        // capabilities.jsonMode = false (default) → prompt fallback path.
        val model = FakeLanguageModel(
            // Loop step: a plain answer.
            listOf(ModelEvent.TextDelta("It's warm in NYC."), ModelEvent.Completed(Usage.ZERO)),
            // Finalization step: fenced JSON.
            listOf(
                ModelEvent.TextDelta("```json\n{\"city\":\"NYC\",\"tempC\":21}\n```"),
                ModelEvent.Completed(Usage.ZERO),
            ),
        )
        val a = agent {
            id = "agent-25"
            name = "t"
            model { custom(model) }
        }
        val weather: CityWeather = a.run<CityWeather>("weather in NYC?", thread = "structured-nyc")
        assertEquals("NYC", weather.city)
        assertEquals(21, weather.tempC)
        // One loop step + one finalization step.
        assertEquals(2, model.calls)
    }

    @Test
    fun run_typed_uses_json_mode_when_supported() = runTest {
        val model = FakeLanguageModel(
            ArrayDeque(
                listOf(
                    listOf(ModelEvent.TextDelta("answer"), ModelEvent.Completed(Usage.ZERO)),
                    listOf(ModelEvent.TextDelta("{\"city\":\"LA\",\"tempC\":30}"), ModelEvent.Completed(Usage.ZERO)),
                )
            ),
            capabilities = ModelCapabilities(jsonMode = true),
        )
        val a = agent {
            id = "agent-26"
            name = "t"
            model { custom(model) }
        }
        val weather: CityWeather = a.run<CityWeather>("weather in LA?")
        assertEquals("LA", weather.city)
        assertEquals(30, weather.tempC)
    }
}
