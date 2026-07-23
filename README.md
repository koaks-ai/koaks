# Koaks

<div align="right">
🌐 &nbsp English | <a href="/README-zh.md">中文</a>
</div>

> The name **"Koaks"** is homophonic with **"coax"**.

<img width="2171" height="724" alt="icon" src="https://github.com/user-attachments/assets/c6ecc47c-57b0-4c48-a4fc-cddc3e810632" />

🧩 **Connect your tools, compose your logic, rule your agents.**

---

## ✨ Highlights

- **One declarative DSL**: `agent { }` assembles an immutable, reusable agent.
- **First-class tools**: define a tool inline with a typed input; its JSON Schema is derived from your `@Serializable` class. Class-based tools, JVM `@Tool` annotations, and lazy **MCP** discovery are all supported.
- **Composable Skills**: load reusable instructions, read-only resources, and tools from `SKILL.md` directories or custom sources.
- **Pluggable memory**: sliding-window, summarizing, or vector memory, committed atomically per turn (a failed or cancelled run won't corrupt history).
- **Structured output**: `agent.run<T>()` returns a typed, decoded result.
- **Resilient by design**: model fallbacks, retry/substitute error policies, step & token budgets, typed hooks, guardrails, and human approval.
- **Kotlin Multiplatform**: JVM, JS, and macOS (Apple Silicon) from a single codebase.

---

## 🚀 Quick Start

### 1. Prerequisites

* Kotlin 2.x / JDK 21 or higher
* Gradle or Maven
* An LLM endpoint + API key (any OpenAI-compatible provider, e.g. Qwen / DeepSeek, or a local Ollama)

> **Warning:** The project is in a rapid iteration phase — the API may change at any time.

### 2. Add Dependencies

The current published group is `org.koaks`. Pick the `koaks-core` runtime plus
the provider module(s) you need.

**Gradle (Kotlin DSL)**
```kotlin
// For Gradle projects — JVM or Kotlin Multiplatform — just add the artifact below.
// Gradle resolves the right platform variant automatically.
implementation("org.koaks:koaks-core:0.0.1-snapshot1")
implementation("org.koaks:koaks-model-qwen:0.0.1-snapshot1")

// Optional add-ons:
// implementation("org.koaks:koaks-model-ollama:0.0.1-snapshot1")
// implementation("org.koaks:koaks-memory-summarizing:0.0.1-snapshot1")
// implementation("org.koaks:koaks-memory-vector:0.0.1-snapshot1")
```

**Maven**
```xml
<!-- For Maven you must pick the platform variant yourself.
     If you're unsure what that means, the JVM variant below is the one you want. -->
<dependency>
  <groupId>org.koaks</groupId>
  <artifactId>koaks-core-jvm</artifactId>
  <version>0.0.1-snapshot1</version>
</dependency>
<dependency>
  <groupId>org.koaks</groupId>
  <artifactId>koaks-model-qwen-jvm</artifactId>
  <version>0.0.1-snapshot1</version>
</dependency>
```

### 3. Your First Agent

```kotlin
import kotlinx.coroutines.runBlocking
import org.koaks.framework.loop.agent
import org.koaks.framework.loop.use
import org.koaks.provider.qwen.qwen

fun main() = runBlocking {
    val agent = agent {
        id = "assistant"
        name = "assistant"
        instructions = "You are a concise, helpful assistant."
        model {
            qwen(
                baseUrl = "base-url",
                apiKey = "api-key",
                modelName = "qwen3-235b-a22b-instruct-2507",
            )
        }
    }

    agent.use {
        val result = it.run("What's the meaning of life?")
        println(result.text)
    }
}
```

`run` drives the agent to a terminal state and returns an `AgentResult`
(`.text`, `.usage`, `.isSuccess`). `agent.use { }` closes the transport the agent owns
when you're done.

**Multi-segment & dynamic instructions.** The `instructions = "..."` shorthand is fine for
a fixed prompt. When you need several pieces — or parts that depend on run-time context —
use the `instructions { }` block instead. Each `dynamic { }` segment is a `suspend`
provider resolved **once per run** (returning `null`/blank omits it); all non-blank
segments are joined with a blank line into the single system prompt.

```kotlin
agent {
    id = "dynamic-assistant"
    instructions {
        +"You are a concise, helpful assistant."     // static
        text("Always answer in English.")            // static (explicit form)
        dynamic { "Today is ${LocalDate.now()}." }    // resolved per run
        dynamic { lookupUserProfile(userId)?.let { "User prefs: $it" } }  // null → skipped
    }
    model { qwen(baseUrl = "...", apiKey = "...", modelName = "qwen3-235b-a22b-instruct-2507") }
}
```

> Both forms coexist; if you set `instructions = "..."` and an `instructions { }` block on the
> same agent, the block wins. See [`DynamicInstructions.kt`](/examples/src/jvmMain/kotlin/examples/DynamicInstructions.kt).
>
> **KV-cache tip:** keep the resolved instructions stable across the turns of a conversation.
> Changing them mid-conversation invalidates the provider's prompt cache, since the system
> prompt sits at the front of every request.

### 4. Streaming Events

`stream` emits the loop's events as they happen — assistant text, the model's reasoning
trace, tool calls, and lifecycle markers.

```kotlin
import org.koaks.framework.loop.AgentEvent

agent.use {
    it.stream("Explain Kotlin coroutines in two sentences.").collect { event ->
        when (event) {
            is AgentEvent.ReasoningDelta    -> print(event.text)          // model thinking
            is AgentEvent.TextDelta         -> print(event.text)          // final answer
            is AgentEvent.ToolCallRequested -> println("\n[tool] ${event.call.name}")
            is AgentEvent.ToolResult        -> println("[result] ${event.output}")
            is AgentEvent.Completed         -> println("\n[done]")
            is AgentEvent.Terminated        -> println("\n[terminated] ${event.reason}")
            is AgentEvent.Failed            -> println("\n[error] ${event.error.message}")
            is AgentEvent.StepCompleted     -> Unit
        }
    }
}
```

### 5. Tools

Define a tool inline with a typed input — its JSON Schema is generated from the
`@Serializable` class, so the model knows exactly what arguments to send.

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
        id = "weather-agent"
        name = "weather-agent"
        instructions = "Answer the user's questions, using tools when needed."
        model {
            qwen(baseUrl = "base-url", apiKey = "api-key", modelName = "qwen3-235b-a22b-instruct-2507") {
                params { parallelToolCalls = true }   // provider-level default
            }
        }
        params { temperature = 0.3 }                  // agent-level params override provider defaults

        tools {
            tool<NoInput>(
                name = "get_city",
                description = "Get the city where the user is located",
            ) { "Shanghai" }

            tool<WeatherInput>(
                name = "get_weather",
                description = "Get the weather for a specific city",
            ) { input -> "${input.city}: cloudy, with a high-wind warning." }
        }

        terminateAfter(maxSteps = 20)
    }

    agent.use {
        println(it.run("What's the weather where I am?").text)
    }
}
```

You can also register **class-based tools** (`Tool<In>`), JVM **`@Tool` annotated**
functions, or connect an **MCP** server whose tools are discovered lazily:

```kotlin
tools {
    tool(MyClassBasedTool())   // implements Tool<In>
    mcp(myMcpGateway)          // tools discovered on first run via tools/list
}
```

### 6. Skills

Skills add reusable instructions, resources, and tools to an Agent. The built-in Markdown
loader expects one direct child directory per Skill:

```text
.agents/skills/
└── code-review/
    ├── SKILL.md
    └── references/conventions.md
```

Each `SKILL.md` starts with YAML front matter; its Markdown body becomes the Skill
instructions:

```markdown
---
name: code-review
description: Review Kotlin code using project conventions
---
Check correctness, concurrency, error handling, and public API compatibility.
```

Configure sources and optional selections when building the Agent:

```kotlin
val agent = agent {
    id = "reviewer"
    model { qwen(baseUrl = "base-url", apiKey = "api-key", modelName = "qwen3-235b-a22b-instruct-2507") }
    skills {
        source(".agents/skills")
        source(customLoader)       // implements SkillLoader

        use("code-review")       // any use(...) switches to a global allow-list
        use("project-conventions")
    }
}

agent.prepare()                   // optional: validate eagerly at service startup
```

`source(path)` uses the built-in `MarkdownDirectorySkillLoader`; `source(loader)` accepts
a custom `SkillLoader` backed by memory, a database, a remote service, or another format.
The default directory filesystem is available on JVM, Native, and Node.js. Browser JS
applications should use `source(customLoader)` with HTTP, IndexedDB, bundled resources,
or another application-owned backend. Core paths are literal and relative paths use the
process working directory; the Koaks CLI additionally expands `~/` to the user's home.
Without `use()`, every discovered Skill is enabled. Once any `use()` is present, only the
selected Skills are loaded, in `use()` order. Discovery reads metadata first and full
definitions only for enabled Skills. The first `run` or `stream` automatically prepares
the Agent if `prepare()` was not called explicitly.

Skill resources remain lazy and read-only. Koaks exposes them to the model through a
bounded, paginated resource tool that only accepts relative paths inside enabled Skills.
Pages use a 1-based line/column cursor, so even a single very long line can be continued
without losing content. Scripts in a Skill package are resources only and are never
executed automatically.

### 7. Memory (Multi-Turn Conversations)

Attach memory to the agent, then pass the same `thread` to each run. `ThreadId` is owned
by the Runtime, so different Agents can join the same conversation. History is loaded on
each turn and committed atomically only when the whole turn finishes — a failure or
cancellation leaves persisted history untouched.

```kotlin
val agent = agent {
    id = "memory-assistant"
    model { qwen(baseUrl = "base-url", apiKey = "api-key", modelName = "qwen3-235b-a22b-instruct-2507") }
    memory {
        window(40)   // sliding-window; or none() / custom("provider-id", provider)
    }
}

agent.use {
    println(it.run("My name is Ada.", thread = "user-1001").text)
    println(it.run("What's my name?", thread = "user-1001").text)   // remembers across turns
}
```

All execution goes through an `AgentRuntime`. The convenient `Agent.run`, `stream`, and
`spawn` methods share a lazily-created process-wide default Runtime. Create an explicit
Runtime when you need a bounded lifecycle, scheduling, quotas, cancellation, or global
observation:

```kotlin
AgentRuntime { maxConcurrency = 8 }.use { runtime ->
    val result = runtime.run(agent, "Continue", thread = "user-1001")
    val events = runtime.stream(agent, "Explain that", thread = "user-1001")
    val handle = runtime.spawn(agent, "Background task", thread = "user-1001")
}
```

`AgentId` identifies the immutable Agent definition, `ThreadId` identifies a long-lived
conversation, `TurnId` identifies one atomic top-level turn, and `RunId` identifies one
execution instance. Top-level turns on the same Thread run FIFO; different Threads remain
concurrent.

### 8. Structured Output

Ask for a typed result and Koaks constrains the final step to valid JSON (native JSON
mode when the model supports it, otherwise a schema-in-prompt fallback) and decodes it.

```kotlin
import org.koaks.framework.loop.run

@Serializable
data class CityWeather(val city: String, val tempC: Int)

agent.use {
    val w: CityWeather = it.run<CityWeather>("What's the weather in Shanghai right now?")
    println("${w.city}: ${w.tempC}°C")
}
```

### 9. Model Fallback & Resilience

```kotlin
agent {
    id = "resilient-assistant"
    model {
        // try Qwen first; fall back to Ollama only if the primary fails before any output
        qwen(baseUrl = "...", apiKey = "...", modelName = "qwen3-235b-a22b-instruct-2507")
            .fallback(ollama(baseUrl = "http://localhost:11434", modelName = "llama3.1"))
    }
    onError(org.koaks.framework.policy.ErrorPolicy.retryRetriable(maxRetries = 2))
    runBudget(maxTotalSteps = 30, maxTotalTokens = 100_000)   // whole-run global guard
}
```

Typed **hooks** can transform model requests/streams and tool calls/results. Push-style
**listeners** (`Tracing`) remain observe-only:

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

## 🧱 Modules

| Module | Artifact | Purpose |
|--------|----------|---------|
| core | `koaks-core` | The agent runtime: DSL, loop, tools, Skills, memory, hooks/listeners, transport |
| qwen | `koaks-model-qwen` | Qwen / OpenAI-compatible provider |
| ollama | `koaks-model-ollama` | Local Ollama provider (NDJSON) |
| memory: summarizing | `koaks-memory-summarizing` | Summarizing long-conversation memory |
| memory: vector | `koaks-memory-vector` | Vector-store-backed memory |
| graph | `koaks-graph` | Graph orchestration (in progress) |

---

## 🤝 Contributing Guide

Thank you for your interest in contributing! Code, documentation improvements, and issues
are all welcome.

1. Fork the repository
2. Create a new branch (`git checkout -b feature-xxx`)
3. Commit your changes (`git commit -m 'Add new feature'`)
4. Push your branch (`git push origin feature-xxx`)
5. Open a Pull Request

> Building from source requires **JDK 21**. Quick check: `./gradlew :tests:jvmTest`.

## 💖 Acknowledgements
This project makes use of, but is not limited to, the following open-source projects:

| Project | Description |
|---------|-------------|
| [Kotlin](https://github.com/JetBrains/kotlin) | The Kotlin Programming Language. |
| [kotlin-logging](https://github.com/oshai/kotlin-logging) | Lightweight multiplatform logging framework for Kotlin. A convenient and performant logging facade. |
