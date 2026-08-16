package examples

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.koaks.framework.loop.AgentEvent
import org.koaks.framework.loop.agent
import org.koaks.framework.loop.tool
import org.koaks.framework.tool.ToolProgress
import org.koaks.provider.openai.responses.ResponsesStateMode
import org.koaks.provider.openai.responses.openaiResponses
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * OpenAI Responses API 演示。密钥加载方式与 [QuickStart] 相同：项目根目录 `.env`
 * 里的 `OPENAI_BASE_URL` / `OPENAI_API_KEY`。
 *
 * 相对 Chat Completions 的差异：
 *  - `openaiResponses { }` 走 `/v1/responses`，不是 `/v1/chat/completions`
 *  - `webSearch()` 是服务端工具，不经过本地 `tools { }`
 *  - 默认 [ResponsesStateMode.Replayable]：`store=false`，把 items（含加密推理）原样回传，
 *    **不会**带 `previous_response_id`。官方服务端链式续写请改用 `ServerStored`。
 */
fun main() = runBlocking {
    val agent = agent {
        id = "responses-quickstart"
        name = "responses-briefing-agent"
        instructions = """
            你是一个简洁的中文助手。需要当地时间或天气时调用本地工具；
            需要外部事实时使用 web search。先给结论，再补一两句依据。
        """.trimIndent()
        model {
            openaiResponses(
                baseUrl = EnvTools.loadValue("OPENAI_BASE_URL"),
                apiKey = EnvTools.loadValue("OPENAI_API_KEY"),
                modelName = "gpt-5.6-terra",
            ) {
                stateMode = ResponsesStateMode.Replayable
                temperature = 0.7
                reasoning = buildJsonObject {
                    put("effort", JsonPrimitive("medium"))
                    put("summary", JsonPrimitive("auto"))
                }
                webSearch()
            }
        }
        tools {
            tool<NoInput>(
                name = "get_local_city",
                description = "获取当前系统所在的城市",
            ) {
                "当前系统所在的城市：西安"
            }
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
                "${input.city} 天气: 晴天，适合出门。"
            }
        }
        terminateAfter(maxSteps = 20)
    }

    agent.use {
        val printer = ResponsesConsolePrinter()
        it.stream(
            "介绍一下自己，告诉我现在几点、今天天气怎么样，并搜一条今日科技新闻。",
        ).collect { event ->
            printer.print(event)
        }
    }
}

private class ResponsesConsolePrinter {
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
            is AgentEvent.ToolProgress -> {
                endInlineSection()
                val value = when (val progress = event.progress) {
                    is ToolProgress.Output -> "${progress.stream.name.lowercase()}: ${progress.text}"
                    is ToolProgress.Status -> progress.message
                    is ToolProgress.Custom -> "${progress.kind}: ${progress.payload}"
                }
                println("${blue("[tool progress]")} ${event.callId} $value")
            }
            is AgentEvent.Completed -> {
                endInlineSection()
                println(
                    green(
                        "[done] tokens=${event.usage.totalTokens} " +
                            "reasoning=${event.usage.reasoningOutputTokens} " +
                            "cached=${event.usage.cachedInputTokens}",
                    ),
                )
            }
            is AgentEvent.Incomplete -> {
                endInlineSection()
                println(red("[incomplete] ${event.reason} tokens=${event.usage.totalTokens}"))
            }
            is AgentEvent.Terminated -> {
                endInlineSection()
                println(red("[terminated] ${event.reason}"))
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
        REASONING(dim("======== Reasoning ========")),
        ASSISTANT(bold("======== Text ========")),
    }
}

private fun bold(text: String): String = "\u001B[1m$text\u001B[0m"
private fun dim(text: String): String = "\u001B[2m$text\u001B[0m"
private fun blue(text: String): String = "\u001B[34m$text\u001B[0m"
private fun green(text: String): String = "\u001B[32m$text\u001B[0m"
private fun red(text: String): String = "\u001B[31m$text\u001B[0m"
