# Koaks-ai

> The name **"Koaks"** is homophonic with **"coax"**.

<div align="right">
🌐 &nbsp<a href="/README.md">English</a> | 中文
</div>

<img width="2171" height="724" alt="icon" src="https://github.com/user-attachments/assets/c6ecc47c-57b0-4c48-a4fc-cddc3e810632" />

🧩 **Connect your tools, compose your logic, rule your agents.**

Koaks 是一个 Kotlin Multiplatform **Agent 框架**。你只需用一个 `agent { }` 代码块描述
Agent——它的指令、模型、工具、记忆与终止规则——Koaks 便会为你运行完整的
"推理 → 行动 → 观察" 循环：流式输出 token、调用工具、把结果回喂给模型，并在终止策略
命中时停止。

---

## ✨ 核心特性

- **一个声明式 DSL** —— `agent { }` 组装出一个不可变、可复用的 Agent。
- **真正的流式** —— 逐 token 的 `TextDelta`、独立的 `ReasoningDelta`（思考过程），以及
  工具调用与生命周期事件，全部即时转发（tee，绝不先聚合再发）。
- **一等公民的工具** —— 用带类型的输入内联定义工具，其 JSON Schema 由你的
  `@Serializable` 类自动生成。同时支持类式工具、JVM 的 `@Tool` 注解，以及 **MCP** 工具的
  懒发现。
- **可插拔的记忆** —— 滑动窗口、摘要式或向量式记忆，按对话轮次原子提交（运行失败或被取消
  绝不会污染历史）。
- **结构化输出** —— `agent.run<T>()` 直接返回解码后的强类型结果。
- **天生健壮** —— 模型回退（fallback）、重试/替换错误策略、步数与 token 预算，以及环绕式
  中间件（缓存、护栏、人工审批）。
- **Kotlin Multiplatform** —— 一套代码同时支持 JVM、JS 与 macOS（Apple Silicon）。

---

## 🚀 快速开始

### 1. 准备

* Kotlin 2.x / JDK 21 或更高版本
* 构建工具：Gradle 或 Maven
* 一个 LLM 端点 + API 密钥（任意 OpenAI 兼容的提供商，例如通义千问 / DeepSeek，或本地 Ollama）

> **注意：当前项目正在快速迭代期，API 随时都有可能发生变化。**

### 2. 引入依赖

当前发布的 group 为 `org.koaks.framework`。引入 `koaks-core` 运行时，再按需选择提供商模块。

**Gradle (Kotlin DSL)**
```kotlin
// 对于 Gradle 项目（无论 JVM 还是 Kotlin Multiplatform），只需添加如下依赖，
// Gradle 会自动解析对应平台的变体。
implementation("org.koaks.framework:koaks-core:0.0.1-snapshot1")
implementation("org.koaks.framework:koaks-model-qwen:0.0.1-snapshot1")

// 可选模块：
// implementation("org.koaks.framework:koaks-model-ollama:0.0.1-snapshot1")
// implementation("org.koaks.framework:koaks-memory-summarizing:0.0.1-snapshot1")
// implementation("org.koaks.framework:koaks-memory-vector:0.0.1-snapshot1")
```

**Maven**
```xml
<!-- 对于 Maven，你需要自己选择平台变体。
     如果你不清楚这是什么意思，那么下面的 JVM 变体就是你需要的。 -->
<dependency>
  <groupId>org.koaks.framework</groupId>
  <artifactId>koaks-core-jvm</artifactId>
  <version>0.0.1-snapshot1</version>
</dependency>
<dependency>
  <groupId>org.koaks.framework</groupId>
  <artifactId>koaks-model-qwen-jvm</artifactId>
  <version>0.0.1-snapshot1</version>
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
            is AgentEvent.Finished          -> println("\n[完成]")
            is AgentEvent.Failed            -> println("\n[错误] ${event.error.message}")
            is AgentEvent.StepCompleted     -> Unit
        }
    }
}
```

### 5. 工具调用

用带类型的输入内联定义工具——其 JSON Schema 由 `@Serializable` 类生成，模型因此清楚地
知道该传哪些参数。

```kotlin
import kotlinx.serialization.Serializable
import org.koaks.framework.loop.agent
import org.koaks.framework.loop.tool
import org.koaks.framework.loop.use
import org.koaks.provider.qwen.qwen

@Serializable
data object NoInput

@Serializable
data class WeatherInput(val city: String)

fun main() = kotlinx.coroutines.runBlocking {
    val agent = agent {
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

### 6. 记忆（多轮对话）

为 Agent 配置记忆，然后通过 `thread(id)` 进行对话。每一轮都会加载历史，并仅在该轮成功结束
时原子提交——运行失败或被取消都不会影响已持久化的历史。

```kotlin
val agent = agent {
    model { qwen(baseUrl = "base-url", apiKey = "api-key", modelName = "qwen3-235b-a22b-instruct-2507") }
    memory {
        window(40)   // 滑动窗口；也可用 none() / custom(摘要式或向量式记忆)
    }
}

agent.use {
    val chat = it.thread("user-1001")
    println(chat.run("我叫 Ada。").text)
    println(chat.run("我叫什么名字?").text)   // 跨轮次记住
}
```

### 7. 结构化输出

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

### 8. 模型回退与健壮性

```kotlin
agent {
    model {
        // 先尝试 Qwen；仅当主模型在产出任何输出之前失败时，才回退到 Ollama
        qwen(baseUrl = "...", apiKey = "...", modelName = "qwen3-235b-a22b-instruct-2507")
            .fallback(ollama(baseUrl = "http://localhost:11434", modelName = "llama3.1"))
    }
    onError(org.koaks.framework.policy.ErrorPolicy.retryRetriable(maxRetries = 2))
    runBudget(maxTotalSteps = 30, maxTotalTokens = 100_000)   // 全程全局护栏
}
```

环绕式**中间件**（`Cache`、`Guardrail`、`HumanApproval`）与推送式**监听器**（`Tracing`）
以同样的方式安装：

```kotlin
agent {
    install(org.koaks.framework.middleware.Tracing)
    // install(Cache(...)); install(Guardrail(...)); install(HumanApproval(...))
}
```

---

## 🧱 模块

| 模块 | Artifact | 用途 |
|------|----------|------|
| core | `koaks-core` | Agent 运行时：DSL、循环、工具、记忆、中间件、传输层 |
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
