package org.koaks.framework.annotation

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.koaks.framework.loop.AgentEvent
import org.koaks.framework.loop.FakeLanguageModel
import org.koaks.framework.loop.agent
import org.koaks.framework.model.ModelEvent
import org.koaks.framework.model.ToolCall
import org.koaks.framework.model.Usage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnnotatedToolTest {

    @Serializable
    @Tool(name = "weather", description = "Get the weather for a city")
    data class WeatherInput(
        @Param("the city name") val city: String,
        @SerialName("unit") val unit: String = "celsius",
    )

    @Test
    fun annotated_tool_delegates_to_regular_tool() = runTest {
        val model = FakeLanguageModel(
            listOf(
                ModelEvent.ToolCallCompleted(ToolCall("c1", "weather", "{\"city\":\"NYC\"}")),
                ModelEvent.Completed(Usage.ZERO),
            ),
            listOf(ModelEvent.TextDelta("It's sunny in NYC"), ModelEvent.Completed(Usage.ZERO)),
        )
        val a = agent {
            name = "t"
            model { custom(model) }
            tools {
                tool(annotatedTool<WeatherInput> { "sunny in ${it.city}" })
            }
            terminateAfter(maxSteps = 5)
        }

        val events = a.stream("weather in NYC?").toList()

        val toolResult = events.filterIsInstance<AgentEvent.ToolResult>().single()
        assertEquals("sunny in NYC", toolResult.output)
        assertTrue(events.any { it is AgentEvent.Finished })
    }

    @Test
    fun annotated_tool_name_and_description_come_from_annotation() {
        val tool = annotatedTool<WeatherInput> { "x" }
        assertEquals("weather", tool.name)
        assertEquals("Get the weather for a city", tool.description)
    }
}
