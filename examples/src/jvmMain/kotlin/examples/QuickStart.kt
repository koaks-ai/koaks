package examples

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.koaks.framework.loop.AgentEvent
import org.koaks.framework.loop.agent
import org.koaks.framework.loop.tool
import org.koaks.provider.qwen.qwen

import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter


fun main() = runBlocking {
    val agent = agent {
        name = "local-time-weather-agent"
        instructions = """
            你是一个简洁的本地助手。当用户询问当地时间或天气时，除非用户给出不同的城市，否则优先使用配置的本地城市/时区。
        """.trimIndent()
        model {
            qwen(
                baseUrl = EnvTools.loadValue("BASE_URL"),
                apiKey = EnvTools.loadValue("API_KEY"),
                modelName = "qwen3.7-plus",
            ) {
                // Qwen-native generation params, bound to this model.
                enableThinking = true
                temperature = 0.7
            }
        }
        tools {
            tool<NoInput>(
                name = "get_local_time",
                description = "获取当前系统所在时区的本地时间",
            ) {
                val zone = ZoneId.of(ZoneId.systemDefault().id)
                val now = ZonedDateTime.now(zone)
                "当前系统所在时区的本地时间：${now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)}"
            }

            tool<WeatherInput>(
                name = "get_weather",
                description = "获取指定城市的天气信息",
            ) { input ->
                getWeather(input.city)
            }
        }

        terminateAfter(maxSteps = 60)
    }

    agent.use {
        val printer = ConsoleEventPrinter()
        it.stream("介绍一下自己，并且告诉我现在几点了？北京的天气怎么样？").collect { result ->
            printer.print(result)
        }
    }
}

private fun getWeather(city: String): String = "$city 天气: 晴天，适合出门。"

@Serializable
private data object NoInput

@Serializable
private data class WeatherInput(
    val city: String,
)

private class ConsoleEventPrinter {
    private var section: Section? = null

    fun print(event: AgentEvent) {
        when (event) {
            is AgentEvent.ReasoningDelta -> {
                startSection(Section.REASONING)
                print(dim(event.text))
            }

            is AgentEvent.TextDelta -> {
                startSection(Section.ASSISTANT)
                print(event.text)
            }

            is AgentEvent.ToolCallRequested -> {
                endInlineSection()
                println("${blue("[tool call]")} ${event.call.name}")
            }

            is AgentEvent.ToolResult -> {
                val label = if (event.isError) red("[tool error]") else green("[tool result]")
                println("$label ${event.output}")
            }

            is AgentEvent.Finished -> {
                endInlineSection()
                println(green("[done]"))
            }

            is AgentEvent.Failed -> {
                endInlineSection()
                println(red("[error] ${event.error.message}"))
            }

            is AgentEvent.StepCompleted -> Unit
        }
    }

    private fun startSection(next: Section) {
        if (section == next) return
        endInlineSection()
        section = next
        println(next.title)
    }

    private fun endInlineSection() {
        if (section != null) {
            println()
            println()
            section = null
        }
    }

    private enum class Section(val title: String) {
        REASONING(dim("======== Reasoning ========")), ASSISTANT(bold("======== Text ========")),
    }
}

private fun bold(text: String): String = "\u001B[1m$text\u001B[0m"
private fun dim(text: String): String = "\u001B[2m$text\u001B[0m"
private fun blue(text: String): String = "\u001B[34m$text\u001B[0m"
private fun green(text: String): String = "\u001B[32m$text\u001B[0m"
private fun red(text: String): String = "\u001B[31m$text\u001B[0m"
