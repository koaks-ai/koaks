package examples

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koaks.framework.annotation.Param
import org.koaks.framework.loop.Agent
import org.koaks.framework.loop.AgentEvent
import org.koaks.framework.loop.agent
import org.koaks.framework.loop.tool
import org.koaks.framework.mcp.McpToolGateway
import org.koaks.framework.mcp.entity.McpTool
import org.koaks.framework.middleware.HumanApproval
import org.koaks.framework.middleware.ToolContext
import org.koaks.framework.tool.ToolProgress
import org.koaks.provider.openai.openai


fun main(args: Array<String>) = runBlocking {
    val modelName = "deepseek-v4-flash"
    val console = ConsoleSession()
    val thread = "weather-demo-chat"

    println(bold("== 创建 Agent =="))

    val savedMemory = UserMemoryStore.read()
    if (savedMemory != null) {
        println("${cyan("[memory]")} ${dim("loaded ${UserMemoryStore.file}")}")
    } else {
        println("${cyan("[memory]")} ${dim("${UserMemoryStore.file} 还不存在，偏好会在 save_memory 时写入")}")
    }

    val agent = agent {
        id = "weather-assistant"
        name = "weather-assistant"
        instructions {
            +"You are a helpful assistant. "
            +"When you learn a lasting user preference (home city, units, language, etc.), call save_memory so it persists across sessions."
            +"When the user asks what you remember about them, call list_memory."
            dynamic {
                UserMemoryStore.read()?.let { "Saved user memory:\n$it" }
            }
        }
        model {
            openai(
                baseUrl = EnvTools.loadValue("DEEPSEEK_BASE_URL"),
                apiKey = EnvTools.loadValue("DEEPSEEK_API_KEY"),
                modelName = modelName,
            ) {
                reasoningEffort = "high"
                temperature = 0.7
            }
        }
        tools {
            tool<NoInput>(
                name = "get_local_city",
                description = "获取当前系统所在的城市",
            ) {
                "当前系统所在的城市：西安"
            }
            tool<SaveMemoryInput>(
                name = "save_memory",
                description = "把用户的持久偏好或事实记到本地 Markdown。已记住的内容不要重复保存。",
                hasSideEffects = true,
            ) { input ->
                UserMemoryStore.append(input.fact)
            }
            tool<NoInput>(
                name = "list_memory",
                description = "列出当前已保存的用户偏好、已经与用户确认的事实/决策或记忆",
            ) {
                UserMemoryStore.list()
            }
            mcp(WeatherMcpGateway())
        }
        memory {
            window(40)
        }
        install(
            HumanApproval(
                guard = { it.call.name == "get_weather" },
                approve = { ctx -> console.askApproval(ctx) },
            ),
        )
        terminateAfter(maxSteps = 64)
    }

    println(dim("多轮对话已开始。输入 exit / quit / q 或 Ctrl-D 结束。"))
    println()

    agent.use { weatherAgent ->
        val first = args.firstOrNull()?.takeIf { it.isNotBlank() }
        if (first != null) {
            runChatTurn(weatherAgent, first, thread)
        }
        while (true) {
            val line = console.readUserTurn() ?: break
            when {
                line.isEmpty() -> continue
                line.equals("exit", ignoreCase = true) ||
                        line.equals("quit", ignoreCase = true) ||
                        line.equals("q", ignoreCase = true) -> break

                else -> runChatTurn(weatherAgent, line, thread)
            }
        }
    }
    println(dim("会话结束。"))
}

private suspend fun runChatTurn(
    agent: Agent,
    query: String,
    thread: String,
) {
    println(dim("--------"))
    val printer = WeatherDemoPrinter()
    agent.stream(query, thread).collect { event ->
        printer.print(event)
    }
    println()
}

/**
 * In-process MCP gateway used by this demo. Swap for
 * `org.koaks.framework.mcp.client.DefaultMcpClient` when talking to a real MCP server.
 */
private class WeatherMcpGateway : McpToolGateway {

    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(8))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun listTools(): List<McpTool> {
        println("${blue("[mcp]")} tools/list")
        return listOf(
            McpTool(
                name = "get_weather",
                description = "获取指定城市的天气信息（温度、天气现象、风力）",
                inputSchema = buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put(
                        "properties",
                        buildJsonObject {
                            put(
                                "city",
                                buildJsonObject {
                                    put("type", JsonPrimitive("string"))
                                    put("description", JsonPrimitive("城市名称，例如西安或上海"))
                                },
                            )
                        },
                    )
                    put("required", buildJsonArray { add(JsonPrimitive("city")) })
                },
            ),
        )
    }

    override suspend fun callTool(name: String, argumentsJson: String): String {
        println("${blue("[mcp]")} tools/call $name")
        if (name != "get_weather") {
            return "unsupported MCP tool: $name"
        }
        val city = parseCity(argumentsJson) ?: return "缺少 city 参数: $argumentsJson"
        return fetchWeather(city)
    }

    private fun parseCity(argumentsJson: String): String? =
        runCatching {
            val element = json.parseToJsonElement(argumentsJson)
            (element as? JsonObject)?.get("city")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        }.getOrNull()

    private suspend fun fetchWeather(city: String): String = withContext(Dispatchers.IO) {
        runCatching { fetchOpenMeteo(city) }.getOrElse { error ->
            println("${yellow("[mcp]")} Open-Meteo 失败，回退 mock：${error.message}")
            "$city 天气: 晴天，适合出门。"
        }
    }

    private fun fetchOpenMeteo(city: String): String {
        val encoded = URLEncoder.encode(city, StandardCharsets.UTF_8)
        val geoUri = URI.create(
            "https://geocoding-api.open-meteo.com/v1/search?name=$encoded&count=1&language=zh",
        )
        val geoBody = httpGet(geoUri)
        val geo = json.parseToJsonElement(geoBody).jsonObject
        val place = geo["results"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: error("未找到城市：$city")
        val latitude = place["latitude"]?.jsonPrimitive?.doubleOrNull
            ?: error("missing latitude")
        val longitude = place["longitude"]?.jsonPrimitive?.doubleOrNull
            ?: error("missing longitude")
        val resolvedName = place["name"]?.jsonPrimitive?.contentOrNull ?: city

        val forecastUri = URI.create(
            "https://api.open-meteo.com/v1/forecast" +
                    "?latitude=$latitude&longitude=$longitude" +
                    "&current=temperature_2m,weather_code,wind_speed_10m" +
                    "&timezone=auto",
        )
        val forecastBody = httpGet(forecastUri)
        val current = json.parseToJsonElement(forecastBody).jsonObject["current"]?.jsonObject
            ?: error("missing current weather")
        val temperature = current["temperature_2m"]?.jsonPrimitive?.contentOrNull ?: "?"
        val wind = current["wind_speed_10m"]?.jsonPrimitive?.contentOrNull ?: "?"
        val code = current["weather_code"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        val condition = weatherDescription(code)
        return "$resolvedName ${temperature}°C $condition 风速 ${wind}m/s"
    }

    private fun httpGet(uri: URI): String {
        val request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) {
            "HTTP ${response.statusCode()} from $uri"
        }
        return response.body()
    }
}

private class ConsoleSession {
    private val mutex = Mutex()
    private val reader = System.`in`.bufferedReader()

    suspend fun readUserTurn(): String? = mutex.withLock {
        withContext(Dispatchers.IO) {
            print(bold("You> "))
            System.out.flush()
            reader.readLine()?.trim()
        }
    }

    suspend fun askApproval(ctx: ToolContext): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            val city = parseCityArg(ctx.call.arguments) ?: ctx.call.arguments
            println("${yellow("[approval]")} ${ctx.call.name} args=${ctx.call.arguments}")
            readYesNo(city)
        }
    }

    private fun readYesNo(city: String): Boolean {
        while (true) {
            print("Approve get_weather for $city? [Y/N] ")
            System.out.flush()
            when (reader.readLine()?.trim()?.uppercase()) {
                null -> {
                    println()
                    println(dim("[reviewer] stdin closed, treating as N"))
                    return false
                }

                "Y", "YES" -> {
                    println(green("[reviewer] approved"))
                    return true
                }

                "N", "NO" -> {
                    println(red("[reviewer] denied"))
                    return false
                }

                else -> println(dim("请输入 Y 或 N"))
            }
        }
    }
}

@Serializable
private data class SaveMemoryInput(
    @Param(name = "fact", description = "要记住的用户偏好或事实，例如：用户常驻城市是西安")
    val fact: String,
)

private object UserMemoryStore {
    private val writeMutex = Mutex()
    val file: Path = resolveFile()

    fun read(): String? {
        if (!Files.exists(file)) return null
        return Files.readString(file).trim().takeIf { it.isNotEmpty() }
    }

    fun list(): String = read() ?: "no saved memory yet"

    suspend fun append(fact: String): String = writeMutex.withLock {
        withContext(Dispatchers.IO) {
            val clean = fact.trim()
            require(clean.isNotEmpty()) { "fact must not be blank" }
            Files.createDirectories(file.parent)
            val existing = if (Files.exists(file)) Files.readString(file) else "# User memory\n"
            val line = "- $clean"
            if (existing.lineSequence().any { it.trim() == line }) {
                return@withContext "already saved: $clean"
            }
            Files.writeString(file, existing.trimEnd() + "\n" + line + "\n")
            println("${cyan("[memory]")} wrote $file")
            "saved: $clean ($file)"
        }
    }

    private fun resolveFile(): Path {
        var current: Path? = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        while (current != null) {
            val examples = current.resolve("examples")
            if (Files.isDirectory(examples) && Files.exists(examples.resolve("build.gradle.kts"))) {
                return examples.resolve("user-memory.md")
            }
            if (current.fileName.toString() == "examples" && Files.exists(current.resolve("build.gradle.kts"))) {
                return current.resolve("user-memory.md")
            }
            current = current.parent
        }
        return Path.of("examples", "user-memory.md").toAbsolutePath()
    }
}

private fun parseCityArg(arguments: String): String? =
    runCatching {
        val element = Json.parseToJsonElement(arguments)
        (element as? JsonObject)?.get("city")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    }.getOrNull()

private fun weatherDescription(code: Int?): String = when (code) {
    0 -> "晴"
    1 -> "大部晴朗"
    2 -> "多云"
    3 -> "阴"
    45, 48 -> "雾"
    51, 53, 55 -> "毛毛雨"
    56, 57 -> "冻毛毛雨"
    61, 63, 65 -> "雨"
    66, 67 -> "冻雨"
    71, 73, 75, 77 -> "雪"
    80, 81, 82 -> "阵雨"
    85, 86 -> "阵雪"
    95 -> "雷暴"
    96, 99 -> "雷暴伴冰雹"
    null -> "天气不明"
    else -> "天气代码 $code"
}

private class WeatherDemoPrinter {
    private var section: Section? = null

    fun print(event: AgentEvent) {
        when (event) {
            is AgentEvent.ReasoningDelta -> append(Section.REASONING, event.text)
            is AgentEvent.TextDelta -> append(Section.ASSISTANT, event.text)

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

            is AgentEvent.Completed -> endInlineSection()

            is AgentEvent.Incomplete -> {
                endInlineSection()
                println(red("[incomplete] ${event.reason}"))
            }

            is AgentEvent.Terminated -> {
                endInlineSection()
                println(red("[terminated] ${event.reason}"))
            }

            is AgentEvent.Failed -> {
                endInlineSection()
                println(red("[error] ${event.error.message}"))
            }

            is AgentEvent.StepCompleted, is AgentEvent.Model -> Unit
        }
    }

    private fun append(next: Section, text: String) {
        if (section != next) {
            if (section != null) {
                println()
                println()
            }
            section = next
            print(next.label)
        }
        print(if (next == Section.REASONING) dim(text) else text)
        System.out.flush()
    }

    private fun endInlineSection() {
        if (section != null) {
            println()
            section = null
        }
    }

    private enum class Section(val label: String) {
        REASONING("Reasoning: "),
        ASSISTANT("Text: "),
    }
}

private fun bold(text: String): String = "\u001B[1m$text\u001B[0m"
private fun dim(text: String): String = "\u001B[2m$text\u001B[0m"
private fun blue(text: String): String = "\u001B[34m$text\u001B[0m"
private fun cyan(text: String): String = "\u001B[36m$text\u001B[0m"
private fun green(text: String): String = "\u001B[32m$text\u001B[0m"
private fun yellow(text: String): String = "\u001B[33m$text\u001B[0m"
private fun red(text: String): String = "\u001B[31m$text\u001B[0m"
