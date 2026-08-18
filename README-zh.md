# Koaks-ai

> The name **"Koaks"** is homophonic with **"coax"**.

<div align="right">
🌐 &nbsp<a href="/README.md">English</a> | 中文
</div>

<img width="2171" height="724" alt="icon" src="https://github.com/user-attachments/assets/c6ecc47c-57b0-4c48-a4fc-cddc3e810632" />

🧩 **Connect your tools, compose your logic, rule your agents.**

---

## ✨ 核心特性

- **一个声明式 DSL**: `agent { }` 组装出一个不可变、可复用的 Agent。
- **一等公民的工具**: 用带类型的输入内联定义工具，其 JSON Schema 由你的 `@Serializable` 类自动生成。同时支持类式工具、JVM 的 `@Tool` 注解，以及 **MCP** 工具的懒发现。
- **可组合的 Skills**: 从 `SKILL.md` 目录或自定义来源加载可复用 instructions、只读资源和工具。
- **可插拔的记忆**: 滑动窗口、摘要式或向量式记忆，按对话轮次原子提交（运行失败或被取消，不污染历史）。
- **结构化输出**: `agent.run<T>()` 直接返回解码后的强类型结果。
- **天生健壮**: 模型回退（fallback）、重试/替换错误策略、步数与 token 预算，以及 Around 中间件（缓存、护栏、人工审批）。
- **Kotlin Multiplatform**: 一套代码同时支持 JVM、JS 与 macOS（Apple Silicon）。

---

## 🚀 快速开始

### 1. 准备

* Kotlin 2.x / JDK 21 或更高版本
* 构建工具：Gradle 或 Maven
* 一个 LLM 端点 + API 密钥（任意 OpenAI 兼容的提供商，例如通义千问 / DeepSeek，或本地 Ollama）

> **注意：当前项目正在快速迭代期，API 随时都有可能发生变化。**
> JVM artifacts 使用 Java 21 字节码，不再支持在 Java 17 运行时中加载。

### 2. 引入依赖

当前发布的 group 为 `org.koaks`。引入 `koaks-core` 运行时，再按需选择提供商模块。

使用快照版本时，需要添加 Central 快照仓库：

```kotlin
repositories {
    mavenCentral()
    maven("https://central.sonatype.com/repository/maven-snapshots/")
}
```

**Gradle (Kotlin DSL)**
```kotlin
// 对于 Gradle 项目（无论 JVM 还是 Kotlin Multiplatform），只需添加如下依赖，
// Gradle 会自动解析对应平台的变体。
implementation("org.koaks:koaks-core:0.0.1-beta5")
implementation("org.koaks:model-openai:0.0.1-beta5")

// 可选模块：
// implementation("org.koaks:model-ollama:0.0.1-beta5")
// implementation("org.koaks:memory-summarizing:0.0.1-beta5")
// implementation("org.koaks:memory-vector:0.0.1-beta5")
```

**Maven**
```xml
<!-- 对于 Maven，你需要自己选择平台变体。
     如果你不清楚这是什么意思，那么下面的 JVM 变体就是你需要的。 -->
<dependency>
  <groupId>org.koaks</groupId>
  <artifactId>koaks-core-jvm</artifactId>
  <version>0.0.1-beta5</version>
</dependency>
<dependency>
  <groupId>org.koaks</groupId>
  <artifactId>koaks-model-openai-jvm</artifactId>
  <version>0.0.1-beta5</version>
</dependency>
```

### 3. 你的第一个 Agent

```kotlin
import kotlinx.coroutines.runBlocking
import org.koaks.framework.loop.agent
import org.koaks.framework.loop.use
import org.koaks.provider.qwen.qwen

fun main() = runBlocking {
    val agent = agent {
        id = "assistant"
        name = "assistant"
        instructions = "你是一个简洁、乐于助人的助手。"
        model {
            qwen(
                baseUrl = "base-url",
                apiKey = "api-key",
                modelName = "qwen3-235b-a22b-instruct-2507",
            )
        }
    }

    agent.use {
        val result = it.run("生命的意义是什么?")
        println(result.text)
    }
}
```

`run` 会驱动 Agent 运行至终止状态，并返回 `AgentResult`（`.text`、`.usage`、
`.isSuccess`）。`agent.use { }` 会在结束时关闭由 Agent 持有的传输层。

**纯 Java 21。** JVM artifacts 同时提供普通 Builder、`CompletableFuture`、JDK
`Flow.Publisher`、虚拟线程 Tool 回调和 Jackson 结构化输出门面：

```java
import org.koaks.framework.loop.AgentResult;
import org.koaks.framework.annotation.Param;
import org.koaks.framework.annotation.Tool;
import org.koaks.java.Agent;
import org.koaks.java.qwen.Qwen;

record WeatherInput(String city) {}
record Weather(String city, int tempC) {}

final class WeatherTools {
    @Tool("查询指定城市天气")
    public String getWeather(WeatherInput input) {
        return input.city() + "：多云";
    }
}

try (Agent agent = Agent.builder()
        .id("weather-agent")
        .instructions("回答天气问题，必要时调用工具。")
        .model(Qwen.builder()
                .apiKey(System.getenv("QWEN_API_KEY"))
                .modelName("qwen-plus")
                .build())
        .tool(new WeatherTools())
        .maxSteps(20)
        .build()) {
    AgentResult result = agent.run("上海天气怎么样？");
    Weather typed = agent.runStructured("以 JSON 返回上海天气", Weather.class);
    System.out.println(result.getText() + " / " + typed);
}
```

Java 方法直接接收标量参数时，框架会将它包装为模型 Tool Calling 所需的对象
Schema。它必须通过 `@Param` 显式提供稳定的 JSON 字段名和描述，不使用参数名推断：

```java
@Tool("查询指定城市天气")
public String getWeather(
        @Param(name = "city", description = "城市名称，例如上海") String city
) {
    return city + "：多云";
}
```

传入对象上所有公开的 `@Tool` 方法都会注册。方法可以没有参数或接收一个
record/POJO 参数，并返回 `String` 或 `CompletionStage<String>`。需要显式 lambda
注册时仍可使用 `Tools.sync`/`Tools.async`。`runAsync` 返回可取消的
`CompletableFuture`，`stream` 返回单订阅 JDK Publisher；需要显式并发和 quota
控制时使用 `AgentRuntime.builder()`。完整注解示例见
[`JavaQuickStart.java`](/examples/src/jvmMain/java/examples/JavaQuickStart.java)。

**多段 & 动态系统指令。** 固定提示词用 `instructions = "..."` 即可。当指令需要分成多段、
或某些片段依赖运行时上下文时，改用 `instructions { }` 块。其中每个 `dynamic { }` 都是一个
`suspend` 提供器，**每次运行解析一次**（返回 `null`/空白则跳过该段）；所有非空片段会以空行
拼接成唯一的系统提示词。

```kotlin
agent {
    id = "dynamic-assistant"
    instructions {
        +"你是一个简洁、乐于助人的助手。"               // 静态
        text("始终用中文回答。")                       // 静态（显式写法）
        dynamic { "今天的日期是 ${LocalDate.now()}。" }   // 每次运行解析
        dynamic { lookupUserProfile(userId)?.let { "用户偏好：$it" } }  // 返回 null 则跳过
    }
    model { qwen(baseUrl = "...", apiKey = "...", modelName = "qwen3-235b-a22b-instruct-2507") }
}
```

> 两种写法可以共存；若在同一个 Agent 上同时设置 `instructions = "..."` 和 `instructions { }` 块，
> 以块为准。参见 [`DynamicInstructions.kt`](/examples/src/jvmMain/kotlin/examples/DynamicInstructions.kt)。
>
> **KV Cache 提示：** 为了 KV Cache 的友好性，建议在整个对话过程中保持 instructions 稳定。
> 系统提示词位于每次请求的最前面，对话中途改变它会使 provider 的 prompt cache 失效。

### 4. 流式事件

`stream` 会即时发出循环中的各类事件——助手文本、模型的思考过程、工具调用以及生命周期标记。

```kotlin
import org.koaks.framework.loop.AgentEvent

agent.use {
    it.stream("用两句话解释 Kotlin 协程。").collect { event ->
        when (event) {
            is AgentEvent.ReasoningDelta    -> print(event.text)         // 思考过程
            is AgentEvent.TextDelta         -> print(event.text)         // 最终答案
            is AgentEvent.ToolCallRequested -> println("\n[工具] ${event.call.name}")
            is AgentEvent.ToolResult        -> println("[结果] ${event.output}")
            is AgentEvent.Completed         -> println("\n[完成]")
            is AgentEvent.Terminated        -> println("\n[已终止] ${event.reason}")
            is AgentEvent.Failed            -> println("\n[错误] ${event.error.message}")
            is AgentEvent.StepCompleted     -> Unit
            is AgentEvent.Model             -> Unit // 仅在请求 LOSSLESS 时发出
            else                            -> Unit
        }
    }
}
```

`EventDetail.SEMANTIC` 是默认值，保持上面这种精简事件序列。界面、录制器或调试器需要
完整逻辑 provider 流时，可以选择 `EventDetail.LOSSLESS`：

```kotlin
import org.koaks.framework.model.EventDetail

agent.stream("解释结果", eventDetail = EventDetail.LOSSLESS).collect { event ->
    when (event) {
        is AgentEvent.TextDelta -> renderText(event.text, event.itemRef)
        is AgentEvent.ReasoningDelta -> renderReasoning(event.text, event.kind, event.itemRef)
        is AgentEvent.Model -> recordModelEvent(event.event, event.step, event.phase)
        else -> Unit
    }
}
```

在 lossless 模式下，Responses、Chat Completions 和 Anthropic 会为每个输入
`WireFrame` 先发出一个 `ModelEvent.ProviderEvent`，再发出派生语义事件。provider
事件记录协议、来源以及未经修改的逻辑 payload。这个保证从 HTTP framing 之后开始，
不保存原始 HTTP 字节、请求头、SSE 注释或连接元数据。文本、推理和已完成工具调用继续
使用现有 `AgentEvent` 投影，不会在 `AgentEvent.Model` 中重复出现。
`ReasoningDelta.kind` 区分 `SUMMARY` 与 `RAW`，`itemRef` 用于把流式 delta 关联到最终 item。

> **敏感数据与日志容量：** lossless payload 可能包含模型文本、工具参数、原始推理、
> 签名或加密 provider 内容，框架不会自动脱敏。详细事件会写入 runtime 的有界 run
> journal；默认容量为 1024 条，因此 lossless run 会更快产生 `history_gap`。
>
> **迁移说明：** `AgentEvent.Model` 是新增的 sealed subtype。外部对 `AgentEvent`
> 使用 exhaustive `when` 时，需要增加该分支或 `else`。

Java 使用 `RunOptions.builder().eventDetail(EventDetail.LOSSLESS).build()`；Node 使用
`{ eventDetail: "lossless" }`。

### 5. 工具调用

用带类型的输入内联定义工具——其 JSON Schema 由 `@Serializable` 类生成，模型因此清楚地
知道该传哪些参数。

```kotlin
import kotlinx.serialization.Serializable
import org.koaks.framework.annotation.Param
import org.koaks.framework.loop.agent
import org.koaks.framework.loop.tool
import org.koaks.framework.loop.use
import org.koaks.provider.qwen.qwen

@Serializable
data object NoInput

@Serializable
data class WeatherInput(
    @Param(name = "city", description = "城市名称，例如西安或上海")
    val city: String,
)

fun main() = kotlinx.coroutines.runBlocking {
    val agent = agent {
        id = "weather-agent"
        name = "weather-agent"
        instructions = "回答用户的问题，必要时调用工具。"
        model {
            qwen(baseUrl = "base-url", apiKey = "api-key", modelName = "qwen3-235b-a22b-instruct-2507") {
                params { parallelToolCalls = true }   // 提供商层默认参数
            }
        }
        params { temperature = 0.3 }                  // Agent 层参数会逐字段覆盖提供商默认值

        tools {
            tool<NoInput>(
                name = "get_city",
                description = "获取用户所在的城市",
            ) { "上海" }

            tool<WeatherInput>(
                name = "get_weather",
                description = "获取指定城市的天气",
            ) { input -> "${input.city}：多云，伴有大风预警。" }
        }

        terminateAfter(maxSteps = 20)
    }

    agent.use {
        println(it.run("我所在的城市天气怎么样?").text)
    }
}
```

你也可以注册**类式工具**（`Tool<In>`）、JVM 的 **`@Tool` 注解**函数，或连接一个
**MCP** 服务器（其工具会在首次运行时被懒发现）：

```kotlin
tools {
    tool(MyClassBasedTool())   // 实现 Tool<In>
    mcp(myMcpGateway)          // 首次运行时通过 tools/list 发现工具
}
```

### 6. Skills

Skill 为 Agent 添加可复用的 instructions、资源和工具。内置 Markdown 加载器要求每个
直接子目录代表一个 Skill：

```text
.agents/skills/
└── code-review/
    ├── SKILL.md
    └── references/conventions.md
```

每个 `SKILL.md` 以 YAML front matter 开头，Markdown 正文会作为该 Skill 的 instructions：

```markdown
---
name: code-review
description: Review Kotlin code using project conventions
---
Check correctness, concurrency, error handling, and public API compatibility.
```

在构建 Agent 时配置来源和可选的启用清单：

```kotlin
val agent = agent {
    id = "reviewer"
    model { qwen(baseUrl = "base-url", apiKey = "api-key", modelName = "qwen3-235b-a22b-instruct-2507") }
    skills {
        source(".agents/skills")
        source(customLoader)       // 实现 SkillLoader

        use("code-review")       // 任意 use(...) 会切换到全局白名单模式
        use("project-conventions")
    }
}

agent.prepare()                   // 可选：在服务启动时提前校验
```

`source(path)` 使用内置 `MarkdownDirectorySkillLoader`；`source(loader)` 可接入以内存、
数据库、远程服务或其他格式为后端的自定义 `SkillLoader`。默认目录文件系统支持 JVM、
Native 和 Node.js；浏览器 JS 应通过 `source(customLoader)` 接入 HTTP、IndexedDB、打包资源
或其他由应用持有的后端。core 中路径按字面解释，相对路径以进程工作目录为基准；Koaks CLI
还会将 `~/` 展开为用户主目录。
没有 `use()` 时会启用所有发现
到的 Skill；一旦出现任意 `use()`，则仅按 `use()` 调用顺序加载白名单中的 Skill。
发现阶段只读取元数据，完整定义只为已启用的 Skill 加载。若未显式调用 `prepare()`，首次
`run` 或 `stream` 会自动完成准备。

Skill 资源保持延迟、只读。Koaks 通过一个受大小限制且支持分页的资源工具将它们提供给模型；
该工具只接受已启用 Skill 目录内的相对路径，并使用从 1 开始的行/列游标，因此超长单行也能
继续读取而不丢内容。Skill 包内脚本仅作为资源，绝不会自动执行。

### 7. 记忆（多轮对话）

为 Agent 配置记忆，然后在每次运行时传入相同的 `thread`。`ThreadId` 由 Runtime 全局管理，
因此不同 Agent 也可以加入同一个会话。每一轮都会加载历史，并仅在整个 Turn 成功结束时原子
提交——运行失败或被取消都不会影响已持久化的历史。

```kotlin
val agent = agent {
    id = "memory-assistant"
    model { qwen(baseUrl = "base-url", apiKey = "api-key", modelName = "qwen3-235b-a22b-instruct-2507") }
    memory {
        window(40)   // 滑动窗口；也可用 none() / custom("provider-id", provider)
    }
}

agent.use {
    println(it.run("我叫 Ada。", thread = "user-1001").text)
    println(it.run("我叫什么名字?", thread = "user-1001").text)   // 跨轮次记住
}
```

所有执行都统一经过 `AgentRuntime`。便捷的 `Agent.run`、`stream`、`spawn` 会共享一个懒加载的
进程级默认 Runtime；当你需要明确生命周期、调度、配额、取消或全局观测时，显式创建 Runtime：

```kotlin
AgentRuntime { maxConcurrency = 8 }.use { runtime ->
    val result = runtime.run(agent, "继续", thread = "user-1001")
    val events = runtime.stream(agent, "解释一下", thread = "user-1001")
    val handle = runtime.spawn(agent, "后台任务", thread = "user-1001")
}
```

`AgentId` 标识不可变的 Agent 定义，`ThreadId` 标识长生命周期会话，`TurnId` 标识一次原子的
顶层回合，`RunId` 标识一次执行实例。同一 Thread 的顶层 Turn 按 FIFO 串行，不同 Thread 仍可并发。

工具或 Agent 协程可以通过 `spawnChild` 创建结构化子运行。默认情况下失败会向父运行传播；
需要自行处理结果时可使用 CAPTURE，临时子运行则不会创建持久化 Thread 绑定：

```kotlin
val result = spawnChild(
    worker,
    input = "检查解析器",
    failurePolicy = ChildFailurePolicy.CAPTURE,
    conversation = ChildConversation.Ephemeral,
).await()
```

### 8. 结构化输出

请求一个强类型结果，Koaks 会把最后一步约束为合法 JSON（模型支持原生 JSON 模式时启用，
否则退化为在提示词中注入 schema），并完成解码。

```kotlin
import org.koaks.framework.loop.run

@Serializable
data class CityWeather(val city: String, val tempC: Int)

agent.use {
    val w: CityWeather = it.run<CityWeather>("现在上海的天气怎么样?")
    println("${w.city}：${w.tempC}°C")
}
```

### 9. 模型回退与健壮性

```kotlin
agent {
    id = "resilient-assistant"
    model {
        // 先尝试 Qwen；仅当主模型在产出任何输出之前失败时，才回退到 Ollama
        qwen(baseUrl = "...", apiKey = "...", modelName = "qwen3-235b-a22b-instruct-2507")
            .fallback(ollama(baseUrl = "http://localhost:11434", modelName = "llama3.1"))
    }
    onError(org.koaks.framework.policy.ErrorPolicy.retryRetriable(maxRetries = 2))
    runBudget(maxTotalSteps = 30, maxTotalTokens = 100_000)   // 全程全局护栏
}
```

带类型的 **Hook** 可以改写模型请求/流、工具调用/结果。推送式**监听器**（`Tracing`）
仍保持只观察：

```kotlin
agent {
    id = "guarded-assistant"
    hook {
        onModelCall {
            before { ctx -> ctx.request }
        }
        onToolCall {
            before { ctx -> if (ctx.call.name == "danger") Deny("blocked") else Proceed }
        }
    }
    install(org.koaks.framework.middleware.Tracing)
    // install(Guardrail(...)); install(HumanApproval(...))
}
```

---

## 🧱 模块

| 模块 | Artifact | 用途 |
|------|----------|------|
| core | `koaks-core` | Agent 运行时：DSL、循环、工具、Skills、记忆、Hook/监听器、传输层 |
| qwen | `koaks-model-qwen` | Qwen / OpenAI 兼容提供商 |
| ollama | `koaks-model-ollama` | 本地 Ollama 提供商（NDJSON） |
| memory: summarizing | `koaks-memory-summarizing` | 长对话摘要式记忆 |
| memory: vector | `koaks-memory-vector` | 基于向量库的记忆 |
| graph | `koaks-graph` | 图编排（开发中） |

---

## 🤝 贡献指南

感谢你对参与贡献感兴趣！我们欢迎你贡献代码、改进文档，或提交问题反馈。

1. Fork 仓库
2. 创建新分支（例如：`git checkout -b feature-xxx`）
3. 提交你的更改（例如：`git commit -m '添加新功能'`）
4. 推送你的分支（例如：`git push origin feature-xxx`）
5. 创建 Pull Request（拉取请求）

> 从源码构建需要 **JDK 21**。快速验证：`./gradlew :tests:jvmTest`。

## 💖 感谢
本项目使用了但不限于以下开源项目：

| Project | Description |
|---------|-------------|
| [Kotlin](https://github.com/JetBrains/kotlin) | The Kotlin Programming Language. |
| [kotlin-logging](https://github.com/oshai/kotlin-logging) | Lightweight multiplatform logging framework for Kotlin. A convenient and performant logging facade. |
