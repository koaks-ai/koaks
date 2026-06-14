# Koaks Agent 框架重构架构设计方案

> 状态:Draft / 提案
> 适用范围:`koaks` 由"带工具循环的 Chat Client"重构为通用 **Agent 框架**
> 关注点:通用架构(暂不展开多平台 target 覆盖问题,但所有设计默认 KMP 友好)

---

## 1. 背景与目标

### 1.1 现状判断

当前 `koaks` 本质是一个 **带 tool-call 循环的 Chat Client**,不是 agent 框架。通用层存在四类结构性问题:

| 问题                  | 位置                                                         | 影响                                                         |
| --------------------- | ------------------------------------------------------------ | ------------------------------------------------------------ |
| **全局可变单例**      | `ToolManager` / `KoaksContext` / `ToolInstanceContainer`(均为 `object`) | 无法多实例隔离、工具名全局撞车、`clientId→tools` map 只增不减泄漏、单测互相污染 |
| **God Class**         | `ChatService`                                                | 消息合并 / memory / 参数 merge / 请求映射 / 流式聚合 / 工具循环 / 错误处理全塞一个类,无扩展点 |
| **无 Agent 一等公民** | 整体                                                         | 无 agent 身份、无推理-行动循环抽象、无多 agent / handoff、终止只有写死的 `MAX_TOOL_CALL_EPOCH = 30` |
| **工具循环僵硬**      | `ChatService.execChat`                                       | **带工具就强制关流式**;`returnDirectly` 仍是 TODO;工具找不到时把 `"Tool X not found"` 当结果喂回模型(静默错误) |

其它:`IMemoryStorage` 抽象太薄(只是按 id 存的 message list、`memoryId` 要手传);chat 路径无 tracing/事件/可观测性。(注:`graph/` 引擎本身设计不差,但与核心 agent 循环是两件事——见 §4 的取舍。)

### 1.2 保留 vs 替换

| 保留 / 升级                                                  | 替换 / 重写                                                  |
| ------------------------------------------------------------ | ------------------------------------------------------------ |
| `Tool<TInput>` 接口 + `createTool` DSL → 升级为核心工具机制  | 全局 `ToolManager`/`KoaksContext`/`ToolInstanceContainer` → 作用域化 registry |
| `TypeAdapter` + provider 映射思路 → 内部化 + 加 `capabilities` | `ChatService` 的 while 工具循环 → 重写为独立的强类型 agent loop |
| `createChatClient {}` DSL 风格 → 升级为 `agent {}`           | 写死的 `MAX_TOOL_CALL_EPOCH` → `TerminationPolicy`           |
| `graph/` 引擎 → **降级为可选的 L5 编排模块(依赖反转:graph → core)**,不再作为核心 agent 循环的底座 | "带工具关流式" → 流式作为底层原语                            |
| kotlinx.serialization 全栈                                   | JVM 反射注解 → 降级为可选语法糖,委托到 `Tool<>`              |
| graph 的拦截器思想 → 作为 **灵感**,在 agent loop 里直接实现 middleware(不引入 graph 依赖) | 薄 `IMemoryStorage` → 分层可插拔 `Memory`                    |

### 1.3 设计四原则

1. **干掉全局状态**:一切作用域化,通过 builder/实例显式传递,无进程级可变 `object`。
2. **流式是底层原语**:模型永远吐 `ModelEvent` 流,agent 对外吐 `AgentEvent` 流;非流式只是 `collect()` 终态。彻底解决"带工具关流式"。
3. **横切与控制流分离**:借鉴 graph 的拦截器思想,但**在 agent loop 内直接实现**;tracing / 重试基础设施 / 缓存 / 护栏 / 人类审批的**环绕部分**是 middleware,不改核心循环。但 `returnDirectly` / handoff / 错误恢复是**控制流决策**,属 loop 一等逻辑,不塞进 middleware(详见 §3.7)。
4. **核心 agent 循环是独立的强类型 loop**:不依赖 graph。基础 ReAct 循环本质就是 `while (!stop) { step() }`,用强类型 `AgentState` 而非 graph 的 `Map<String, Any>`。graph 是**可选的 L5 编排能力**,依赖方向是 `graph → core`(graph 节点可承载 agent),而非反过来。

---

## 2. 分层架构

```
┌──────────────────────────────────────────────────────────────────────┐
│ L5 Orchestration   handoff、sub-agent、agent-as-tool、planner、显式 workflow│
│  (可选模块,依赖 koaks-core;显式 DAG 编排用 graph,确定性流程才需要)        │
├──────────────────────────────────────────────────────────────────────┤
│ L4 Agent           instructions + model + tools + memory + 终止策略 + 结构化输出│
│                    独立强类型 loop;middleware 环绕(不依赖 graph)         │
├──────────────────────────────────────────────────────────────────────┤
│ L3 Tool System     Tool<In> + 作用域 ToolRegistry + 工具执行 middleware      │
├──────────────────────────────────────────────────────────────────────┤
│ L2 Conversation    Thread/Session + 可插拔 Memory(window/summarizing/vector)│
├──────────────────────────────────────────────────────────────────────┤
│ L1 Model           LanguageModel: generate → Flow<ModelEvent>;ModelCapabilities│
│                    provider 私有 request/response 映射内部化                │
├──────────────────────────────────────────────────────────────────────┤
│ L0 Transport       HTTP/SSE、重试、超时、限流、连接复用(可插拔)            │
├──────────────────────────────────────────────────────────────────────┤
│ 横切               Event/Trace 总线、不可变 Context、协程取消、无全局 DI      │
└──────────────────────────────────────────────────────────────────────┘
```

> 模块映射:L0–L4 全部落在 `koaks-core`(含 Transport 接口与 KtorTransport 实现、ChatModel 连接层);provider 实现在 `koaks-model:*`;L5 在可选的 `koaks-graph`。

### 2.1 模块划分

两条相互独立的轴,别混为一谈:

**轴一:模型接入(几乎人人都用,不是可选项)**
接真实模型走 `koaks-core` + 一个 `koaks-model:*`(如 `koaks-model:qwen`/`:openai`/`:claude`)。**用户无需自己实现 `LanguageModel`**——provider 已实现好,只在 `agent { model { qwen(...) } }` 里配置。模型连接层(Transport/ChatModel/wire 映射)已并入 `koaks-core`,故 core 自带 HTTP 连接能力(引入 Ktor)。(唯一不引 `koaks-model:*` 的情形:自研模型 / 内网网关 / 测试 mock,此时用户自行实现 `LanguageModel` 接口——属逃生口,非主路径。)

**轴二:可插拔组件——用我们的中间件 vs 自己实现接口(真正的选择)**
这才是"用框架提供的 vs 只借 AgentLoop 自己实现"的分野,作用对象是 **Memory / Tool / AgentMiddleware(Tracing/Retry/缓存/护栏)/ TerminationPolicy**:
- 用我们的:`memory { window(40) }`、`install(Tracing)`、`terminateAfter(maxSteps=10)` 等开箱即用实现。
- 自己实现:实现对应接口(`Memory`/`AgentMiddleware`/…)塞进去,只依赖 `koaks-core` 暴露的接口与 AgentLoop,不碰我们的实现。

> 即:无论走哪条轴,模型接入层(`koaks-core` 内的连接层 + 一个 `koaks-model:*`)通常都在;变的只是 §轴二 的那些组件用我们的还是自己写。

```
koaks/
├── koaks-core            # 核心 + 模型连接层。依赖 kotlinx-serialization + coroutines + Ktor
│   ├── loop              #   AgentRunner / AgentState / AgentEvent / Agent + AgentBuilder DSL
│   ├── model             #   统一 Message/ContentPart/ToolCall、LanguageModel 接口、
│   │                     #   ChatRequest、ModelEvent、ModelCapabilities
│   ├── transport         #   Transport 接口 + KtorTransport 实现(连接复用/重试/超时/限流)
│   ├── provider          #   ChatModel<TReq,TResp> 抽象基类 + 有状态流式解码器 + WireAdapter/ModelConfig
│   ├── tool              #   Tool<In> 接口、作用域化 ToolRegistry(实例)、ToolOutcome
│   ├── memory            #   Memory 接口 + NoMemory / WindowMemory(轻量默认实现)
│   ├── middleware        #   AgentMiddleware 接口、Recovery
│   └── policy            #   TerminationPolicy + maxSteps/maxTokens/and(默认实现)
│
├── koaks-model:qwen / :ollama   # provider 实现:仅写 toWire/newDecoder/capabilities,依赖 koaks-core
├── koaks-graph           # 可选 L5:显式 DAG 编排引擎。依赖 koaks-core(依赖反转 graph → core)
└── tests
```

> 说明(相对早期草案的两处实质修正):
> - **AgentLoop 与模型连接层都在 `koaks-core`**。早期草案曾把 L4 拆为独立 `koaks-agent`、把连接层拆为独立 `koaks-model`;现一并合入 core。代价:core 引入 Ktor,core-only 用户也带上 Ktor 传递依赖。收益:模块更少、依赖链短;`koaks-model` 这个名字让位给真正放模型实现的 provider 模块。
> - **`koaks-model:qwen`/`:ollama` 是 provider 实现模块**(由原 `llms:*` 改名)。只实现 `toWire`/`newDecoder`/`capabilities`,经 `koaks-core` 的 `ChatModel` 基类接入,不感知 agent 概念。
> - `koaks-graph` 是**可选**模块,面向"开发者想自己掌控控制流(确定性流水线 / 显式多 agent DAG)"的场景,依赖方向 `graph → core`。若近期不需要显式编排,可搁置或删除,核心不受影响。

---

## 3. 核心抽象

### 3.1 统一消息 / 内容模型(L1 基座)

多模态、工具调用、结构化输出全部走同一套不可变模型:

```kotlin
@Serializable
data class Message(
    val role: Role,
    val parts: List<ContentPart>,
    val id: String? = null,
)

enum class Role { SYSTEM, USER, ASSISTANT, TOOL }

@Serializable
sealed interface ContentPart {
    @Serializable data class Text(val text: String) : ContentPart
    @Serializable data class Image(val url: String? = null, val base64: String? = null) : ContentPart
    @Serializable data class Audio(val url: String? = null, val base64: String? = null, val format: String) : ContentPart
    @Serializable data class ToolCallPart(val call: ToolCall) : ContentPart
    @Serializable data class ToolResultPart(val callId: String, val output: String) : ContentPart
}

@Serializable
data class ToolCall(val id: String, val name: String, val arguments: String /* raw JSON */)
```

provider 的职责只剩:把这套统一模型翻译成自家 wire format,再翻回来(沿用现有 `toChatRequest`/`toChatResponse` 思路)。

### 3.2 流式作为底层原语(L1)

```kotlin
// 模型层事件:只表达 provider 能真实产出的东西,不包含 tool 执行、step、终态等 agent loop 语义。
sealed interface ModelEvent {
    data class TextDelta(val text: String) : ModelEvent
    data class ToolCallDelta(
        val id: String,
        val index: Int? = null,
        val nameDelta: String? = null,
        val argumentsDelta: String? = null,
    ) : ModelEvent
    data class ToolCallCompleted(val call: ToolCall) : ModelEvent
    data class Completed(val usage: Usage) : ModelEvent
    data class Failed(val error: AgentError.ModelError) : ModelEvent
}

// Agent 层事件:由 AgentRunner 对 ModelEvent + tool/memory/termination 状态翻译后对外暴露。
sealed interface AgentEvent {
    data class TextDelta(val text: String) : AgentEvent
    data class ToolCallRequested(val call: ToolCall) : AgentEvent
    data class ToolResult(val callId: String, val output: String) : AgentEvent
    data class StepCompleted(val step: Int) : AgentEvent
    data class HandoffOccurred(val from: String, val to: String) : AgentEvent   // 多 agent 移交,见 §4.3.1
    data class Finished(val message: Message, val usage: Usage) : AgentEvent
    data class Failed(val error: AgentError) : AgentEvent
}

interface LanguageModel {
    val capabilities: ModelCapabilities
    fun generate(request: ChatRequest): Flow<ModelEvent>
}

data class ModelCapabilities(
    // 合理默认:能流式、能调工具、能并行工具调用;vision/jsonMode 这类按模型差异大的默认关,用时显式开。
    val streaming: Boolean = true,
    val tools: Boolean = true,
    val parallelToolCalls: Boolean = true,
    val vision: Boolean = false,
    val jsonMode: Boolean = false,
)
```

**关键**:`generate` 永远返回 `Flow<ModelEvent>`。provider 只负责把 wire chunk 解码成模型事件,不产生 `ToolResult` / `StepCompleted` / `Finished` 等 loop 事件。`AgentRunner` 负责把 `ModelEvent.TextDelta` 透传成 `AgentEvent.TextDelta`,把完整的 `ModelEvent.ToolCallCompleted` 翻译成 `AgentEvent.ToolCallRequested`,再执行工具、终止策略和 memory commit。非流式 = `Flow<ModelEvent>` 只有少量完整事件的退化情形。`ModelCapabilities` 让运行时自适应(不支持 jsonMode 就用 prompt 兜底,决定并行工具等),而不是默认所有 provider 都是 OpenAI 语义。

> **能力由开发者在 DSL 显式声明,框架不维护任何"模型→能力"表**。Agent 开发者清楚自己选的模型支不支持 vision/jsonMode/并行工具——这本就是选型时的已知信息,框架去内置一张表既维护不动(模型迭代远快于框架发版),又有时效性问题,纯属画蛇添足。`ModelCapabilities` 给一组合理默认(见上),开发者只需在 `model { qwen(...) }` 里覆盖与默认不同的项即可:
>
> ```kotlin
> model {
>     qwen(modelName = "qwen-vl-max") {
>         capabilities { vision = true; jsonMode = true }   // 只声明与默认不同的
>     }
> }
> ```
>
> provider 可以为自家"旗舰默认模型"给一个稍微贴合的默认 `capabilities`(纯属便利,非必须),但**最终以开发者 DSL 声明为准**。运行时只管读 `capabilities` 自适应,不查表、不猜测。

### 3.3 Provider 抽象(`koaks-core` 的 provider 层,provider 实现在 `koaks-model:*`)

```kotlin
abstract class ChatModel<TReq, TResp>(
    val config: ModelConfig,                 // baseUrl / apiKey / modelName / defaultParams
) : LanguageModel {

    protected abstract val adapter: WireAdapter<TReq, TResp>   // KSerializer 对
    protected abstract fun toWire(req: ChatRequest): TReq

    // 有状态流式解码:不是无状态纯函数。见下方"关键"
    protected abstract fun newDecoder(): WireDecoder<TResp>

    final override fun generate(req: ChatRequest): Flow<ModelEvent> = flow {
        val decoder = newDecoder()
        transport.stream(config, toWire(req), adapter).collect { chunk ->
            decoder.accept(chunk).forEach { emit(it) }   // 一个 chunk 可产 0..N 个事件
        }
        decoder.finish().forEach { emit(it) }            // flush 残留(完成的 tool call / usage)
    }
    // transport 由构造注入,见 L0
}

// 有状态:累积文本 delta;跨多个 chunk 拼装 ToolCall 的 name/arguments,
// 组装完整后才 emit ModelEvent.ToolCallCompleted。这是流式语义的真实形态。
interface WireDecoder<TResp> {
    fun accept(chunk: TResp): List<ModelEvent>
    fun finish(): List<ModelEvent>
}
```

provider(qwen/ollama)只实现 `toWire`/`newDecoder`/`adapter`/`capabilities`,与 agent 完全解耦。

> **关键(易被低估的工作量)**:OpenAI/Qwen 等流式语义下,助手文本是一串 `delta`,而 **tool call 的 `name` 与 `arguments` 也是跨多个 chunk 分片传输**的。因此 provider 侧需要的是一个**有状态解码器**(累积分片、组装完整 `ToolCall` 后再 emit `ModelEvent.ToolCallCompleted`),而非早期草案设想的无状态 `fromWire(resp): ModelEvent` 纯函数映射。非流式只是"只有一个 chunk"的退化情形,同一条解码路径即可覆盖。

### 3.4 Transport(L0,可插拔)

```kotlin
interface Transport {
    fun <TReq, TResp> stream(config: ModelConfig, req: TReq, adapter: WireAdapter<TReq, TResp>): Flow<TResp>
}
// 默认实现:KtorTransport(连接复用 + 重试 + 超时 + 限流策略)
```

HTTP 客户端不再由 `ChatService` 内部 new(现状每个 service 建一个),而是作为可复用、可配置策略的依赖注入。

> **重试分层:Transport 重试与 loop `Recovery.Retry` 必须分工,否则次数相乘**。两层都能重试,若不划清边界,会出现 transport 重 3 次 × loop 重 2 次 = 实际打 6 次模型的隐患。定死如下:
> - **Transport 层(L0)负责连接级、传输级的透明重试**:连接失败、DNS、5xx、**首字节到达前的超时**。这类失败下游根本没收到任何 `ModelEvent`,重试对上层完全透明、无副作用,是 transport 的天职。重试预算(次数/退避)配在 `KtorTransport`。
> - **loop 的 `Recovery.Retry`(§4.2)只接管 transport 重试耗尽后仍失败、且语义上可重开的情况**,典型是"换个措辞重发整轮"这类**业务级**重试,而非连接级重试。它不该再去重试本属 transport 职责的连接抖动。
> - **一旦已向下游 emit 过任何 `TextDelta`,两层都不得重试**(§4.2 的流式约束):流中途断裂归 `Propagate`。`ModelError.retriable=true` 仅对"首字节前失败"成立,且该判断在 transport 重试耗尽后才冒泡到 loop —— 所以 loop 看到的 retriable 错误已是"transport 尽力过仍失败的首包前错误",loop 再决定是否值得做一次业务级重开,二者不重叠。
>
> 一句话:**连接抖动归 Transport,会话级重开归 loop,透传一个字节后谁都不重试**。

> **资源生命周期必须有主**(现状泄漏的根因)。Transport 持有 Ktor `HttpClient`(连接池),是 `Closeable`。约定:Transport 由 `agent {}` 构造时创建并由 `Agent` 持有;`Agent` 实现 `AutoCloseable`,`close()` 关闭 Transport。多个 agent 可共享同一个外部注入的 Transport(此时所有权归调用方,agent 不 close 外部传入的)。提供 `use { }` 作用域写法。**绝不**像现状那样每个 service `new` 一个 client 却无人 close。

### 3.5 工具系统(L3,作用域化)

```kotlin
interface Tool<In> {
    val name: String
    val description: String
    val inputSerializer: KSerializer<In>
    val returnDirectly: Boolean get() = false
    val hasSideEffects: Boolean get() = false   // 有外部副作用(发邮件/扣款/写库)的工具须置 true,影响回滚语义见 §4.5
    suspend fun execute(input: In): String   // 结果即喂回模型的字符串;复杂结果由实现自行序列化
}

// 作用域化注册表 —— 实例,不是全局 object
class ToolRegistry {
    private val tools = LinkedHashMap<String, Tool<*>>()
    fun register(tool: Tool<*>) {
        require(tool.name !in tools) { "duplicate tool: ${tool.name}" }   // 局部唯一,不污染全局
        tools[tool.name] = tool
    }
    fun toSchemas(): List<ToolSchema>                  // 由 KSerializer 生成 JSON schema(非"自动",见下方注)
    suspend fun call(name: String, argsJson: String): ToolOutcome   // 找不到 → 返回 Failure,不伪造结果
}

sealed interface ToolOutcome {
    data class Success(val output: String, val returnDirectly: Boolean) : ToolOutcome
    data class Failure(val error: AgentError) : ToolOutcome      // 显式错误通道,不再把错误字符串喂回模型
}
```

**`AgentError` 是一等错误模型**(全栈共用,middleware 的 `onError`、`ToolOutcome.Failure`、`AgentEvent.Failed` 都以它为载荷)。错误**分类**直接决定 `Recovery` 能否精准重试(网络抖动可重试、参数解析失败不可重试),因此它是 `sealed`,而非裸 `Throwable`/`String`:

```kotlin
sealed interface AgentError {
    val message: String
    val cause: Throwable?

    // 模型/传输层:通常可重试
    data class ModelError(override val message: String, val retriable: Boolean,
                          override val cause: Throwable? = null) : AgentError
    // 工具执行抛异常:是否可重试由工具语义决定
    data class ToolError(val toolName: String, override val message: String,
                         val retriable: Boolean, override val cause: Throwable? = null) : AgentError
    // 工具/结构化输出的 JSON 解析失败:不可重试(重试同样的输入还是失败)
    data class ParseError(override val message: String, val raw: String,
                          override val cause: Throwable? = null) : AgentError
    // 工具未找到:配置错误,不可重试,显式暴露而非伪造 "Tool X not found" 喂回模型
    data class ToolNotFound(val toolName: String) : AgentError {
        override val message get() = "tool not found: $toolName"
        override val cause: Throwable? get() = null
    }
    // 超时:可重试
    data class Timeout(val stage: String, val elapsedMs: Long) : AgentError {
        override val message get() = "$stage timed out after ${elapsedMs}ms"
        override val cause: Throwable? get() = null
    }
}
```

> 这同时定死了现状的一个静默 bug:`ToolExecutor.call` 把 `"Tool $toolname not found"` 当**正常结果**喂回模型。新设计里它是 `ToolOutcome.Failure(ToolNotFound(name))`,由 loop 决定如何处置(默认作为一条 tool message 回填,但带 `isError` 标记,且经 `AgentEvent.Failed` 暴露给可观测层)。

> **设计取舍**:工具只保留单类型参数 `Tool<In>`,`execute` 直接返回喂回模型的 `String`。不引入 `Tool<In,Out>` + `outputSerializer`——那会与 `ToolOutcome.Success.output: String` 矛盾,且对绝大多数工具是过度设计。需要结构化结果的工具,自行在 `execute` 内序列化即可。这与现有 `Tool<T>`(`execute(input: T): String?`)的语义一致,迁移成本低。

注解式 `@Tool` 降级为 **JVM 上的可选语法糖**:编译/委托到一个 `Tool<>`,不再走独立的反射执行路径,跨平台一致。

> **`toSchemas()` 不是"自动",是一个独立子任务**。从 `SerialDescriptor` 在 commonMain 纯生成 JSON Schema,遇到嵌套对象、`sealed`、枚举、`List`/`Map`、可空、`@SerialName`、默认值时都要逐一处理;现有代码是靠 JVM 注解/反射(`AnnoTypeExecutor`)做的,跨平台拿不到。要么自研一个 `SerialDescriptor → JSON Schema` 编译器,要么引第三方。建议在 §8 路线里单列为一个工作项,别当一行糖估工期。

### 3.6 Memory(L2,分层可插拔)

```kotlin
interface Memory {
    suspend fun load(thread: ThreadId): List<Message>
    suspend fun commit(thread: ThreadId, messages: List<Message>)
}

object NoMemory : Memory                                   // 手动管理(core)
class WindowMemory(val maxMessages: Int) : Memory          // 滑动窗口(core);裁剪以 turn 为原子单位,见 §4.5
class SummarizingMemory(val maxTokens: Int, val model: LanguageModel) : Memory  // 超限自动摘要(独立模块 koaks-memory:summarizing)
class VectorMemory(val store: VectorStore) : Memory        // 语义召回(独立模块 koaks-memory:vector)

// 会话显式建模,threadId 由 session 携带,不再每次手传 memoryId。
// run 边界负责 load/commit,loop 内部不碰 Memory —— 数据流见 §4.5
class Thread(private val agent: Agent, val id: ThreadId) {
    suspend fun run(input: String): AgentResult
    fun stream(input: String): Flow<AgentEvent>
}
```

### 3.7 Middleware(横切) vs 循环控制(loop 一等逻辑)

**先划清边界**(早期草案在这里有概念混淆,务必厘清):并非"一切皆 middleware"。有两类东西,职责不同、落点不同:

| 类别                | 例子                                          | 落点                          | 为什么 |
| ------------------- | --------------------------------------------- | ----------------------------- | ------ |
| **环绕型横切**      | tracing、cache、guardrail、工具超时           | `AgentMiddleware`(环绕)       | 不改变控制流,只在 step 周围做事,核心循环零改动 |
| **循环控制**        | `returnDirectly`、handoff、错误恢复、终止     | **loop 一等逻辑**(while 内分支)| 它们要决定"是否继续/跳转/break",middleware 的返回值表达不了 |

middleware 接口只覆盖**环绕型横切**:

```kotlin
interface AgentMiddleware {
    // 契约收窄:实现要么原样返回 next()(透传),要么返回一个自造的流(缓存命中/短路,根本不调模型)。
    // 绝不允许在这里 collect next() —— 见下方"flow 双消费"。
    suspend fun aroundModelCall(ctx: StepContext, next: suspend () -> Flow<ModelEvent>): Flow<ModelEvent> = next()
    suspend fun aroundToolCall(ctx: ToolContext, next: suspend () -> ToolOutcome): ToolOutcome = next()
}

// 想"看"事件(tracing、token 计数、日志)的用推送式监听,绝不 collect 流 —— 从接口层面消灭双消费。
interface AgentListener {
    fun onModelEvent(event: ModelEvent) {}     // 由 loop 在 §4.1 的 tee 单点推送
    fun onAgentEvent(event: AgentEvent) {}
    fun onStep(state: AgentState) {}
}
```

> **flow 双消费的坑(必须从接口设计上根除,而非靠纪律)**:`aroundModelCall` 拿到的 `next()` 是一个 **cold `Flow<ModelEvent>`**。若某个 middleware 为了统计 token 而 `collect` 了它,而 loop 自己在 §4.1 也要 collect(透传 + 累积),则**同一个 cold flow 被订阅两次 → 真实模型请求发两次**(还可能产生两份计费、两份 token 流)。
>
> 早期把"观察流"和"环绕调用"混在 `aroundModelCall` 一个口子里是错误根源。本次拆成两类扩展点,职责互斥:
> - **`aroundModelCall` 只做"选择用哪个流",绝不消费流**。两种合法返回:① `next()` 原样透传(loop 单订阅,正常调模型);② 一个自造的流(如缓存命中,直接 `flowOf(cachedEvents...)`,**根本不调** `next()`,模型零请求)。这样无论走哪条,流都只被 loop 这一个消费者订阅。
> - **想观察每个事件的(tracing / token 计数)用 `AgentListener`**,由 loop 在 tee 单点(§4.1 `acc.observe` 旁边)把事件推给所有 listener。listener 是推送式,永不持有/订阅流,天然不存在双消费。
>
> 即:`install(Cache)` 走 `aroundModelCall`(短路型),`install(Tracing)` 走 `AgentListener`(观察型)。需要"既改流又看流"的极少数场景,用 `aroundModelCall` 返回一个 `next().onEach { ... }` 包装流(把观察逻辑挂在 loop 的那条唯一订阅上,而不是自己再起一条订阅)。

**错误恢复不在 middleware 里,而在 loop 里**。原因:`Recovery.Retry` 要让外层 while 重跑 model step,`Recovery.Substitute` 要改写 state 后继续——这些都是控制流决策,middleware 的环绕返回值做不到。因此 `Recovery` 由 loop 在捕获 `AgentError` 后消费(见 §4),middleware 至多提供"建议的 Recovery",最终决策权在 loop:

```kotlin
sealed interface Recovery {
    data object Propagate : Recovery                          // 抛出,终止 agent
    data class Retry(val delayMs: Long, val maxRetries: Int) : Recovery   // loop 重跑当前 step
    data class Substitute(val message: Message) : Recovery    // 用替代消息续跑,不重试
}

// 错误处理策略是独立接口,由 loop 调用(不是 middleware 的环绕方法)
fun interface ErrorPolicy {
    fun decide(error: AgentError, state: AgentState): Recovery
}
```

**`returnDirectly` 同理是循环控制,不是 middleware**。它的语义是"工具执行完直接结束 agent loop",而 `aroundToolCall` 返回的 `ToolOutcome` 无法让外层 while break。真正消费 `ToolOutcome.Success.returnDirectly` 的是 **loop 本身**(见 §4):某个工具 `returnDirectly=true` 时,loop 跳过下一轮 model step,直接 `Finished`。

> 结论:tracing/重试基础设施/缓存/护栏/人类审批的"环绕"部分是 middleware;但 `returnDirectly`、handoff、错误恢复的**控制流决策**是 loop 一等逻辑。把后者塞进 middleware 是早期草案的概念错误,本次修正。

### 3.8 终止策略(L4)

```kotlin
fun interface TerminationPolicy {
    fun shouldStop(state: AgentState): Boolean
    companion object {
        fun maxSteps(n: Int): TerminationPolicy
        fun maxTokens(n: Int): TerminationPolicy
        fun and(vararg p: TerminationPolicy): TerminationPolicy
    }
}
```

取代写死的 `MAX_TOOL_CALL_EPOCH = 30`。

---

## 4. 执行模型:独立的强类型 agent loop

核心 agent 循环就是一个 `while`,**不引入 graph**。它只有 model / tool 两个步骤和一个"是否还有 tool call"的分支——为它套通用 graph 引擎是过度设计,而且会把强类型状态退化成 graph 的 `Map<String, Any>`。

```
START → model step → 有 tool call?
                       ├─ yes → tool step(并行执行)
                       │         ├─ 某工具 returnDirectly → 结束
                       │         └─ 否则 → 回到 model step
                       └─ no  → 结束
```

### 4.1 流式的真实数据流:边透传边累积(tee),不是先收完再 emit

这是整个执行模型最容易写错、也最关键的一点。**model step 必须一边把 `ModelEvent.TextDelta` 翻译成 `AgentEvent.TextDelta` 并即时 `emit` 给下游,一边旁路累积出 assistant message 和分片拼装的 ToolCall**;flow 跑完后才读累积结果决定分支。

错误写法(看似合理,实则把流式买没了——等价于旧 `StreamingAggregator` 先聚合):

```kotlin
val turn = collectTurn(events)   // ❌ 必须收完整轮才返回,token 攒完才一次性放出 → 假流式
emitAll(turn.events)
```

正确写法是"tee":collect 上游同时做两件事——透传 + 累积。累积器就是 §3.3 的 `WireDecoder` 思想在 loop 侧的延续:

```kotlin
// 单轮 model step 的累积器:透传由调用方在 collect 时即时 emit,这里只负责攒终态
class TurnAccumulator {
    private val text = StringBuilder()
    private val toolCalls = LinkedHashMap<String, ToolCallBuilder>()   // 跨事件拼装
    private var usage: Usage = Usage.ZERO

    fun observe(event: ModelEvent) {
        when (event) {
            is ModelEvent.TextDelta         -> text.append(event.text)
            is ModelEvent.ToolCallDelta     -> toolCalls.getOrPut(event.id) { ToolCallBuilder() }.merge(event)
            is ModelEvent.ToolCallCompleted -> toolCalls.getOrPut(event.call.id) { ToolCallBuilder() }.merge(event.call)
            is ModelEvent.Completed         -> usage = event.usage
            else -> {}
        }
    }
    fun assistantMessage(): Message = Message.assistant(text.toString(), toolCalls.values.map { it.build() })
    fun toolCalls(): List<ToolCall> = toolCalls.values.map { it.build() }
    fun usage(): Usage = usage
}
```

### 4.2 Loop 骨架(透传 + 工具分支 + returnDirectly + 错误恢复)

```kotlin
class AgentRunner(private val agent: Agent) {

    fun stream(initial: List<Message>): Flow<AgentEvent> = flow {
        var state = AgentState(messages = initial, step = 0)   // 强类型状态,非 Map<String, Any>

        while (!agent.termination.shouldStop(state)) {
            val acc = TurnAccumulator()

            // model step —— middleware 环绕(只环绕,不决定控制流)
            val source = agent.middlewares.foldRight({ agent.model.generate(state.toRequest()) }) { mw, next ->
                { mw.aroundModelCall(state.toStepContext(), next) }
            }()

            // 关键:边收边翻译/透传(emit),同时旁路累积(acc.observe)。token 立即到下游。
            try {
                source.collect { event ->
                    acc.observe(event)
                    when (event) {
                        is ModelEvent.TextDelta ->
                            emit(AgentEvent.TextDelta(event.text))          // ← 即时透传,不等整轮
                        is ModelEvent.ToolCallCompleted ->
                            emit(AgentEvent.ToolCallRequested(event.call))  // 完整 tool call 才对外请求执行
                        is ModelEvent.Failed ->
                            throw ModelFailure(event.error)                 // 直接携带 AgentError 跳出 collect,不绕 throwable 往返
                        else -> {}
                    }
                }
            } catch (t: Throwable) {
                // 统一恢复入口:把"模型显式失败 / 真实异常 / 取消"归一到一个 AgentError 决策点
                val error: AgentError = when (t) {
                    is ModelFailure          -> t.error            // 已是 AgentError,直接用,不再 toAgentError()
                    is CancellationException -> throw t            // 协程取消不拦截,原样向上传播
                    else                     -> t.toAgentError()   // 工具/传输等真实异常才转换
                }
                when (val r = agent.errorPolicy.decide(error, state)) {
                    is Recovery.Retry      -> { delay(r.delayMs); continue }       // 重跑当前 step
                    is Recovery.Substitute -> { state = state.append(r.message); continue }
                    is Recovery.Propagate  -> { emit(AgentEvent.Failed(error)); return@flow }
                }
            }

            val assistant = acc.assistantMessage()
            state = state.append(assistant)
            emit(AgentEvent.StepCompleted(state.step))

            val calls = acc.toolCalls()
            if (calls.isEmpty()) { emit(AgentEvent.Finished(assistant, acc.usage())); return@flow }

            // tool step —— 并行执行;Failure 走显式通道(回填带 isError 的 tool message)
            val outcomes = coroutineScope {
                calls.map { call -> async { agent.tools.callWithMiddleware(call, agent.middlewares) } }.awaitAll()
            }
            outcomes.forEachIndexed { i, o -> emit(o.toEvent(calls[i].id)) }
            state = state.appendToolResults(calls, outcomes)

            // returnDirectly 是循环控制(§3.7):任一工具 returnDirectly → 跳过下一轮 model step,直接结束
            outcomes.firstOrNull { it is ToolOutcome.Success && it.returnDirectly }?.let { direct ->
                val out = (direct as ToolOutcome.Success).output
                emit(AgentEvent.Finished(Message.assistant(out), acc.usage()))
                return@flow
            }
        }
        // 终止策略命中(maxSteps/maxTokens)而非自然结束:也要给下游一个终态
        emit(AgentEvent.Finished(state.lastAssistantOrEmpty(), state.usage))
    }

    suspend fun run(initial: List<Message>): AgentResult = stream(initial).toResult()
}
```

要点对照前面的修订:
- **流式不再被买没**:`collect { emit(...) }` 即时把 `ModelEvent` 翻译成 `AgentEvent` 透传,`acc` 旁路累积,二者并行(§4.1)。
- **错误恢复在 loop 里消费 `Recovery`**,不在 middleware 里(§3.7)。Retry-on-stream 的语义明确为"重跑当前 step";因此**重试只在本轮 emit 任何 `TextDelta` 之前安全**——一旦已透传半截文本再 Retry 会产生重复 token。落地约束:`ModelError.retriable` 仅对"首字节前失败"(连接、鉴权、首包超时)置 true;流中途断裂归为不可重试的 `Propagate`,交由上层重开会话。这一 tradeoff 是 stream 重试绕不开的,显式写死在策略里。
- **错误归一到单一 `AgentError` 决策点,不绕 throwable 往返**:`ModelEvent.Failed` 已携带 `AgentError`,用内部载体 `ModelFailure` 跳出 collect 后直接取出,不再 `asThrowable()→toAgentError()` 转两道;只有工具/传输的**真实异常**才 `toAgentError()`。`CancellationException` 必须**原样向上抛**,绝不当错误吞进恢复逻辑——否则结构化并发的取消语义被破坏(下游 cancel 了 loop 还在 retry)。
- **`returnDirectly` 由 loop 消费**,middleware 碰不到它(§3.7)。
- **终止策略命中也补一个 `Finished`**,下游永远收到终态,不会静默挂起。

```kotlin
// loop 内部载体:把 provider 显式上报的 ModelEvent.Failed 转成异常以跳出 collect,
// 同时原样携带 AgentError,避免 AgentError→Throwable→AgentError 的有损往返。仅 loop 内部可见。
private class ModelFailure(val error: AgentError) : Exception(error.message, error.cause)
```

### 4.3 handoff:loop 内"激活 agent"可变,仍不依赖 graph

handoff 会中途切换 instructions/model/tools——2 节点循环表达不了"换一个 agent"。解法不是上 graph,而是让 loop 持有一个**可变的"当前激活 agent"**:router 决定移交时,替换 `current` 并把累计的 `state.messages` 带过去续跑。控制流仍是同一个 while,只是每轮的 `model`/`tools`/`instructions` 取自 `current`:

```kotlin
var current: Agent = agent
while (!current.termination.shouldStop(state)) {
    // ... model step 用 current.model / current.tools ...
    // 若本轮产出 handoff 信号(由特殊 tool 或 router 解析):
    current.router?.decide(state)?.let { target -> current = target /* state.messages 原样带过去 */; continue }
}
```

这样 §6 的 `handoffs(billingAgent, techSupportAgent)` 有了落地机制:每个 handoff 目标是一个完整 `Agent`,router 切换 `current` 即可,无需 graph。只有**开发者显式编排确定性 DAG** 时才上 `koaks-graph`(§6)。

#### 4.3.1 handoff 后的 step / budget 归属(多 agent 正确性硬伤,必须定死)

朴素写法 `while (!current.termination.shouldStop(state))` 有一个致命歧义:**换了 `current` 后,循环条件用的是 target 的终止策略,但 `state.step` 是从最初 agent 一路累加的**。后果:A 配 `maxSteps=10` 跑了 8 步后 handoff 给 B(B 配 `maxSteps=5`),B 一进来就因 `state.step=8 > 5` 立即终止——几乎肯定不是预期。token 预算同理:到底是全局共享还是各算各的?不定义就是 bug。

定死**两级预算分离**:

```kotlin
// 整轮 run 的全局护栏:跨所有 handoff 累加,防失控(总步数、总 token、墙钟)。归属"这一次 run",不属任何单个 agent。
data class RunBudget(val maxTotalSteps: Int, val maxTotalTokens: Int, val deadlineMs: Long? = null)

class AgentState(
    val messages: List<Message>,
    val globalStep: Int,          // 跨 handoff 累加,RunBudget 用它 —— 绝不因 handoff 重置
    val localStep: Int,           // 当前 agent 自接管以来的步数,current.termination 用它 —— handoff 时重置为 0
    val usage: Usage,             // 累计 token,RunBudget 用它 —— 不重置
    val activeAgentName: String,
)
```

- **`current.termination`(per-agent maxSteps)消费 `localStep`**:每次 handoff 切 `current` 时 `localStep` 归零,让 target 拿到完整的自有步数额度。"A 跑 8 步"不会吃掉 "B 的 5 步"。
- **`RunBudget`(整轮全局护栏)消费 `globalStep` / `usage`**:跨 handoff 持续累加,是防止 "A↔B 互相 handoff 无限循环" 的最终刹车。它独立于任何 agent 的 termination,由 `AgentRunner` 在最外层强制检查。
- **token 预算默认全局共享**(计入 `RunBudget.maxTotalTokens`),不随 handoff 重置——因为账单是一次 run 的总账。

循环条件因此是两个谓词的并集:

```kotlin
var current: Agent = agent
while (!runBudget.exceeded(state) && !current.termination.shouldStop(state /* 看 localStep */)) {
    // ... model step 用 current.model / current.tools ...
    current.router?.decide(state)?.let { target ->
        current = target
        state = state.copy(localStep = 0, activeAgentName = target.name)  // 仅 localStep 归零;globalStep/usage 不动
        emit(AgentEvent.HandoffOccurred(from = ..., to = target.name))    // handoff 是可观测事件
        return@let
    }
}
```

> 一句话:**per-agent 的 maxSteps 看 `localStep`(handoff 重置),整轮的 RunBudget 看 `globalStep`/`usage`(永不重置)**。前者是"每个专家的耐心",后者是"整通对话的总刹车",两者缺一不可。`AgentEvent` 需新增 `HandoffOccurred(from, to)` 以便可观测层追踪移交链。

**为什么不用 graph**:
- **更简单**:2 节点 + 1 分支的循环,`while` 一目了然,没有 route 解析 / 图校验 / 节点调度的额外开销。
- **更类型安全**:`AgentState` 是强类型 data class,而 graph 的 `GraphContext` 是 stringly-typed 的 `Map<String, Any>`,用它承载 agent 状态是倒退。
- **解耦**:agent 的演进不被 graph 的 API 绑架;middleware 在循环里直接 fold,拦截器思想保留,依赖不保留。
- **与业界一致**:OpenAI Agents SDK、Pydantic AI 等都是"简单循环 + handoff",graph-first 是 LangGraph 的特例(graph 是其头牌产品,而非 agent 的内部实现)。

> graph 真正的用武之地是**开发者显式编写的确定性流程**(如 `extract → validate →(若失败)repair → summarize`),那是 L5 的可选能力,见 §6,不是核心循环的底座。

---

## 4.5 Memory 与 AgentState 的数据流(必须定死,否则两套历史会打架)

`AgentState.messages`(单次 run 的工作集)与 `Memory`(跨 run 的持久历史)是两套消息序列。早期草案给了 `Thread.run` 的漂亮 API,却没定义二者怎么流动——这会导致历史丢失或重复。定死如下:

```
thread.run(input):
  1. history   = memory.load(thread.id)                    // 取持久历史(可能已被窗口/摘要裁过)
  2. user      = UserMessage(input)
  3. initial   = history + user
  4. pending   = TurnCommitBuffer(user)                    // 本轮新增消息先进入本地 buffer,不落盘
  5. for event in AgentRunner(agent).stream(initial):       // loop 全程只读写 AgentState,不碰 memory
        pending.observe(event)                             // 记录本轮 assistant / tool 消息
        转发给调用方
  6. 若收到 AgentEvent.Finished 且 flow 正常完成:
        memory.commit(thread.id, pending.messagesInOrder()) // user + assistant + tool 一次性整轮提交
     若未收到 Finished(异常 / cancel / 终态失败):
        丢弃 pending,不污染持久历史
```

**关键约定:**
- **loop 内部不直接调用 `Memory`**。loop 只认 `AgentState`;持久化由 `Thread` 在 run 边界(开始 load、成功结束后 commit)负责。这样 loop 保持纯粹、可单测,Memory 实现可独立替换。
- **落盘时机是 run 成功结束后的整轮提交,不是每条消息即时落盘**。用户输入也不提前写入;中途失败的半轮不污染持久历史(要么 user + assistant + tool 全部提交,要么全部丢弃);也避免 commit 与窗口裁剪交织出竞态。注意:`AgentEvent.Failed` 可以是非终态可观测事件(例如工具错误被回填后继续),是否提交只看最终是否正常收到 `AgentEvent.Finished`。
- **窗口/摘要裁剪发生在 `load` 侧,不在 `commit` 侧**。`commit` 永远忠实追加本轮消息;`load` 时按策略产出"喂给模型的视图"。这样持久层是完整账本,裁剪只是读视图。

> 若业务需要保留失败输入(例如客服审计),不要塞进 `Memory`;应走单独的 audit/event sink。`Memory` 的语义是"可再次喂给模型的成功会话历史",因此默认只提交成功 turn。

> **⚠ 副作用工具与"整轮原子提交"语义不兼容(必须显式警告,否则副作用会重复执行)**。"整轮失败就丢弃 `pending`"对纯计算/只读工具是对的,但对**有外部副作用的工具**(发邮件、扣款、写第三方 DB)是陷阱:一个长 turn 已经成功发了邮件,随后模型步骤失败或被 cancel → 整轮 `pending` 被丢弃 → 下次 run 时会话历史里**没有"邮件已发"的痕迹** → 模型很可能**重发邮件**。这不是 Memory 的 bug,而是"原子提交"语义与"副作用不可回滚"的本质冲突。
>
> 处置原则(三选一,按工具语义):
> 1. **幂等化(首选)**:给副作用工具传幂等键(idempotency key),外部系统去重。这样即便重复调用也安全,原子提交语义可原样保留。
> 2. **副作用结果旁路落账**:副作用工具执行后,其结果**不只进 `pending`,同时写入一个独立的、即时落盘的 side-effect ledger**(不等整轮成功)。下次 run 时,`load` 侧除了读 Memory,还要把未被整轮提交、但已发生副作用的记录补进上下文,让模型知道"邮件已发"。
> 3. **缩小 turn 边界**:把"含副作用工具的调用"切成自己的一轮提交,使副作用与其历史记录原子绑定,而非裹在大 turn 里随大 turn 一起回滚。
>
> 框架默认采用整轮原子提交(对绝大多数只读工具正确);**带副作用的工具必须由开发者显式标注 `Tool.hasSideEffects = true`**,框架在该工具参与的 run 失败回滚时 `log.warn` 提示"已发生的副作用未记入持久历史,后续 run 可能重复执行",并建议走上述三策之一。沉默回滚带副作用的 turn 是不可接受的。

**裁剪必须保持 tool-call 配对完整**(易踩的坑):`WindowMemory(40)` 若把一条"assistant 含 ToolCall"裁掉、却留下对应的"tool result",或反之,provider 会报错(孤儿 tool message)。因此裁剪单位是**完整的 turn(assistant + 其所有 tool results 作为一个原子段)**,而非单条 message:

```kotlin
class WindowMemory(val maxMessages: Int) : Memory {
    // load 时按 turn 边界裁剪,绝不切断 assistant↔toolResult 配对
    override suspend fun load(thread: ThreadId): List<Message> =
        store[thread].dropTurnsToFit(maxMessages)   // 以 turn 为原子单位丢弃最旧的若干 turn
    override suspend fun commit(thread: ThreadId, messages: List<Message>) {
        store.appendAll(thread, messages)            // 忠实追加本轮成功消息
    }
}
```

**模块归属一锤定音**(消除 §3.6 与 §8.2 的自相矛盾):`NoMemory` / `WindowMemory` 在 `koaks-core`(零额外依赖);`SummarizingMemory`(依赖一个 `LanguageModel` 做摘要)与 `VectorMemory`(依赖 VectorStore)**放独立模块 `koaks-memory:summarizing` / `:vector`**,不进 core。§3.6 把 Summarizing 写进 core 是早期笔误,以此处为准。

---

## 5. 对外 API:最标准的 Kotlin DSL

范式 = **带接收者的函数类型 + `@DslMarker` + 顶层工厂函数**(Ktor / Compose / Gradle KTS / kotlinx.html 同款)。DSL 只负责拼装不可变对象,逻辑零持有,可被纯构造函数绕过。

### 5.1 调用点

```kotlin
val agent = agent {
    name = "research-assistant"
    instructions = "You are a careful research assistant. Always cite sources."

    model {
        qwen(modelName = "qwen3-235b-a22b-instruct-2507") {
            temperature = 0.3
            maxTokens = 2048
        }
    }

    memory { window(maxMessages = 40) }          // none() / summarizing(...) / vector(store)

    tools {
        tool(WeatherTool(weatherClient))         // 类实现
        tool<SearchInput>(                        // 内联 DSL,reified 推断 schema
            name = "search",
            description = "Search the web",
        ) { input -> httpSearch(input.query) }
        tool(name = "now", description = "current time") { Clock.System.now().toString() }
        mcp("http://localhost:3000")              // 一行接入 MCP 工具组(延迟发现,见下方说明)
    }

    install(Tracing)                              // middleware,像 Ktor 的 install
    install(RetryOnError(maxRetries = 2))

    terminateAfter(maxSteps = 10)
}
```

> **MCP 接入是异步的,但 builder 是同步的——这道缝必须显式处理**。MCP 工具靠运行时握手 + `tools/list` 动态发现(全是 suspend,现有 `DefaultMcpClient` 已如此)。而 `agent {}` / `tools {}` 是普通同步 lambda,无法在其中 await 工具列表。**不让 builder 变 suspend**(那会毁掉 DSL 手感),改为**延迟发现**:`mcp(url)` 只登记一个 `LazyToolSource`(记下 url/配置),首次 `run`/`stream` 时由 `AgentRunner` 在 suspend 上下文里完成 `tools/list` 并把发现到的工具并入 `ToolRegistry`(发现一次、缓存复用)。代价:首个请求多一次握手延迟;收益:DSL 仍是纯同步拼装。`ToolRegistry` 因此需要支持"运行时追加一批工具",而非只在构造期固定。

### 5.2 执行:suspend 终态 + Flow 流式

```kotlin
// 一次性终态
val result = agent.run("北京今天天气怎么样?")
println(result.text)

// 流式(工具型 agent 也能流式)
agent.stream("...").collect { event ->
    when (event) {
        is AgentEvent.TextDelta -> print(event.text)
        is AgentEvent.ToolCallRequested -> log("调用 ${event.call.name}")
        else -> {}
    }
}

// 结构化输出:reified 泛型直接拿类型化对象
@Serializable data class CityWeather(val city: String, val tempC: Int, val summary: String)
val weather: CityWeather = agent.run<CityWeather>("北京天气?")
// 实现要点:依据 model.capabilities.jsonMode 决定走原生 JSON mode 还是 prompt 兜底
// (在 instructions 注入 schema 约束),并对模型输出做容错解析(剥离 ```json 围栏、
// 提取首个 JSON 对象)后再 decodeFromString,避免直解脆裂。
// 结构化输出 + 工具并存的坑:许多 provider 的 json mode 与 tool calling 不能同时开启。
// 策略:工具循环阶段不开 json mode(让模型自由调工具);当 loop 判定为"最后一轮、无更多 tool call"
// 时,再单独发一次"仅约束输出格式"的收尾请求(开 json mode 或注入 schema)拿结构化结果。
// 即"先把工具跑完,最后一步才约束格式",而非全程强制 JSON。

// 多轮会话:thread 携带历史,不手传 memoryId
val thread = agent.thread("user-1001")
thread.run("第一句")
thread.run("接着上文继续")
```

### 5.3 实现骨架(DSL 是构造函数的薄糖衣)

```kotlin
@DslMarker annotation class AgentDsl            // 关死作用域,防止内层 lambda 串味

@AgentDsl
class AgentBuilder {
    lateinit var instructions: String
    var name: String = "agent"
    private var model: LanguageModel? = null
    private var memory: Memory = NoMemory
    private val tools = ToolRegistry()
    private val middlewares = mutableListOf<AgentMiddleware>()
    private var termination = TerminationPolicy.maxSteps(10)

    fun model(block: ModelScope.() -> Unit) { model = ModelScope().apply(block).build() }
    fun memory(block: MemoryScope.() -> Unit) { memory = MemoryScope().apply(block).build() }
    fun tools(block: ToolScope.() -> Unit) { ToolScope(tools).apply(block) }
    fun install(m: AgentMiddleware) { middlewares += m }
    fun terminateAfter(maxSteps: Int) { termination = TerminationPolicy.maxSteps(maxSteps) }

    fun build(): Agent = Agent(
        name, instructions, requireNotNull(model) { "model is required" },
        memory, tools, middlewares.toList(), termination,
    )
}

// 顶层工厂函数 —— 入口
inline fun agent(block: AgentBuilder.() -> Unit): Agent = AgentBuilder().apply(block).build()

// provider 作为扩展函数挂在受控作用域 ModelScope 上(沿用现有 qwen() 思路,但收进 @DslMarker)
fun ModelScope.qwen(modelName: String, block: QwenConfig.() -> Unit = {}) { /* ... */ }

// reified 工具与结构化输出
inline fun <reified In> ToolScope.tool(
    name: String, description: String, noinline execute: suspend (In) -> String,
) = register(InlineTool(name, description, serializer<In>(), execute))

suspend inline fun <reified T> Agent.run(prompt: String): T =
    Json.decodeFromString(serializer<T>(), runRaw(prompt, OutputSpec.json(serializer<T>())).text)
```

### 5.4 用到的地道 Kotlin 特性

| 特性                                         | 作用                                                         |
| -------------------------------------------- | ------------------------------------------------------------ |
| 带接收者函数类型 `Builder.() -> Unit`        | 配置块 `agent { }`                                           |
| `@DslMarker`                                 | 阻止内层 lambda 误用外层成员(现状 `ModelSelector` 裸扩展缺这层保护) |
| 顶层 `inline fun agent(block)`               | 声明式入口                                                   |
| `inline` + `reified`                         | `tool<In>` / `run<T>` 拿类型、自动 schema                    |
| 默认参数                                     | 干掉望远镜式重载                                             |
| `sealed` + `data class`                      | 事件 / 内容 / 结果 / 错误建模                                |
| `suspend fun run()` + `fun stream(): Flow<>` | 终态与流式统一                                               |
| `fun interface`                              | `TerminationPolicy` 单方法可 lambda                          |

---

## 6. 多 Agent / 编排(L5,可选)

多 agent 的两种基础形态**不需要 graph**,直接在 L4 之上展开:

```kotlin
// agent-as-tool:把一个 agent 包装成另一个 agent 的工具
val translator = agent { /* ... */ }
val main = agent {
    tools { tool(translator.asTool(name = "translate", description = "translate text")) }
}

// handoff:在 agent loop 内由 router 决定把控制权移交给哪个 sub-agent
val triage = agent {
    instructions = "Route the request to the right specialist."
    handoffs(billingAgent, techSupportAgent)
}
```

**只有当开发者想显式编排确定性流程时,才引入 `koaks-graph`**(可选模块)。典型场景:固定流水线、需要可视化/可调试的 DAG、人工设计的多 agent 工作流。这时 graph 的节点里承载 agent:

```kotlin
// koaks-graph 依赖 koaks-core:节点内运行 agent,控制流由开发者掌控而非 LLM
val pipeline = createGraph("doc-pipeline") {
    val extract  = node("extract")  { ctx -> ctx["draft"] = extractorAgent.run(ctx.input()).text }
    val validate = node("validate") { ctx -> ctx["ok"] = validate(ctx["draft"]) }
    val repair   = node("repair")   { ctx -> ctx["draft"] = repairAgent.run(ctx["draft"]).text }

    start to extract to validate
    conditional(from = validate, router = { if (it["ok"] == true) "summarize" else "repair" }) {
        "repair" to repair
        "summarize" to /* ... */
    }
    repair to validate
}
```

planner-executor、反思循环等既可以写成 agent loop 内的策略,也可以(若需要确定性与可视化)写成 graph;**graph 不是必需品**。

---

## 7. 可观测性与错误处理

- **分层事件流**:`ModelEvent` 是 provider 内部流式原语;`AgentEvent` 是对外流式输出,也是 trace 的天然事件源。
- **Tracing middleware**:`install(Tracing)` 在每个 model/tool step 周围打 span,零侵入。
- **显式错误通道**:`ToolOutcome.Failure` / `AgentEvent.Failed` / `AgentError`,不再 log 后伪造字符串塞回模型。
- **预算与终止**:`TerminationPolicy.and(maxSteps(10), maxTokens(50_000))` 组合控制。

---

## 8. 落地路线

### 8.1 第一步:垂直切片(验证架构)

打通现在最痛的一条路径,端到端跑通后再铺开:

```
LanguageModel(流式) → 作用域 ToolRegistry → 独立的强类型 agent loop → 一个 Tracing middleware
验收:一个带工具的 agent 能"边流式输出 token 边调用工具并继续",且工具错误走显式通道
```

> **JSON Schema 生成器必须进切片,不能推到 §8.2(否则掩盖最大风险)**。`SerialDescriptor → JSON Schema`(§3.5)是整个重构里**最容易拖期、最容易出正确性 bug** 的单点,而它又是工具调用的地基:schema 错 → 模型传参错 → 所有工具调用全挂。若切片阶段用**手写 schema** 跑通,等于把这个最大风险藏到第二阶段才引爆,且届时已有一堆代码依赖它。
> 因此切片就要包含一个**最小但真实的 schema 生成器**,覆盖工具调用的高频形态:flat data class + 基本类型(String/Int/Boolean/Double)+ 枚举 + 可空字段 + `List<基本类型>` + `@SerialName`。**暂不**要求覆盖 `sealed`/polymorphic/泛型/递归/`Map`/contextual——那些留给 §8.2 第 2 项完整实现,但切片用的工具入参必须真用这个生成器产出 schema,而非手写。验收加一条:**切片工具的 schema 由 `toSchemas()` 实际生成并被模型成功解析调用**。

> 模块归属:接口(`LanguageModel`/`Tool`/`Transport`)、`KtorTransport`、`ChatModel` 连接层、`ToolRegistry`、`AgentRunner`、`AgentMiddleware` 全在 `koaks-core`;切片阶段先在一个 provider 模块(`koaks-model:qwen`)里实现真实流式 `ChatModel` 打通端到端。

> **切片必须验证的两个易错点**(它们正是 §4 的修订重点,不验证等于没做):①「边 emit token 边累积」的 tee 行为——下游要能在工具调用前就先收到文本 token,而非整轮攒完;②工具未找到 → `ToolOutcome.Failure(ToolNotFound)` → `AgentEvent.Failed`,而非把 `"Tool X not found"` 当结果喂回模型(复刻现状 bug 即视为切片失败)。

### 8.2 后续阶段

1. **连接层完善**:`ModelCapabilities` 驱动的自适应、`KtorTransport` 重试/限流策略、**有状态流式解码器**(跨 chunk 拼装 tool call 的 name/arguments,见 §3.3);qwen/ollama 迁移到新 `ChatModel`。
2. **JSON Schema 生成器**(独立工作项,勿低估):`SerialDescriptor → JSON Schema` 的 commonMain 纯实现,覆盖嵌套/`sealed`/枚举/`List`/`Map`/可空/默认值;`toSchemas()` 依赖它。这是脱离 JVM 反射、跨平台一致的前提。
3. **L2 Memory**:`koaks-core` 内 `NoMemory`/`WindowMemory`(默认实现,裁剪以 turn 为原子单位);`SummarizingMemory`/`VectorMemory` 入独立模块 `koaks-memory:*`;`Thread` 会话化并按 §4.5 的数据流接 load/commit。
4. **L3 工具**:`@Tool` 注解(JVM 语法糖)委托到 `Tool<In>`;MCP 工具按**延迟发现**(§5.1)实现并接入 `ToolRegistry`(需支持运行时追加)。
5. **L4 完善**:`TerminationPolicy` 组合、`ErrorPolicy`/`Recovery` 接入 loop(§3.7/§4.2)、结构化输出(含 `jsonMode` fallback 与「工具跑完最后一步才约束格式」§5.2)、middleware 生态(Retry/Cache/Guardrail/HumanApproval)、`Agent` 的 `AutoCloseable` 资源生命周期(§3.4)。
6. **L5 多 agent**:`asTool` / `handoffs`(loop 内「激活 agent」可变,§4.3)/ planner-executor。
7. **(可选)L5 显式编排**:仅当需要确定性 DAG 编排时,把 `koaks-graph` 的 `GraphContext` 改为类型安全状态,并让其依赖 `koaks-core`。非必需,可按需推进。

### 8.3 重构策略(不保持兼容)

本次为**彻底重构,不保留对现有 `createChatClient {}` / `ChatService` 的兼容**。旧的 `ToolManager`/`KoaksContext`/`ToolInstanceContainer` 全局 `object`、`ChatService` God Class、`AbstractChatModel` 的非流式映射,直接删除重写,不做过渡封装。

- 新入口统一为 `agent {}`;不再保留 `createChatClient {}`。
- provider DSL(`qwen()` 等)重写为挂在 `@DslMarker` 的 `ModelScope` 上的扩展函数,签名以新 `ChatModel`/`ModelConfig` 为准,不迁就旧 `ModelSelector`。

---

## 9. 决策摘要

1. **无全局状态** —— `ToolManager`/`KoaksContext` 等全部实例化、作用域化。
2. **流式优先且真透传** —— `LanguageModel.generate(): Flow<ModelEvent>` 是模型层唯一原语;`AgentRunner.stream(): Flow<AgentEvent>` 是对外原语;loop 边翻译 emit 边累积(tee,§4.1),终结"带工具关流式",且不退化为先聚合再吐。
3. **横切与控制流分离** —— tracing/cache/guardrail 是 middleware(环绕);`returnDirectly`/handoff/错误恢复是 loop 一等逻辑(§3.7)。不再笼统说"一切皆 middleware"。
4. **核心 loop 不依赖 graph** —— agent 循环是独立强类型 `while`,落在 `koaks-core`;graph 降级为**可选的 L5 显式编排模块**,依赖反转为 `graph → core`。
5. **DSL 薄糖衣 + reified 类型安全** —— `@DslMarker` + `agent {}` + `tool<In>` + `run<T>`。
6. **provider 解耦** —— 只实现 `toWire`/`newDecoder`(有状态解码)/`capabilities`,不感知 agent。
7. **注解降级** —— `@Tool` 变 JVM 语法糖,核心统一到 `Tool<In>` + `KSerializer`。
8. **Memory 数据流定死** —— load/commit 在 run 边界,loop 内不碰 Memory;成功 turn 整轮提交,失败/取消不落盘;裁剪以 turn 为原子单位,Summarizing/Vector 入独立模块(§4.5)。
9. **错误是一等模型** —— `sealed AgentError` 带可重试分类;`ErrorPolicy → Recovery` 在 loop 消费(§3.5/§4.2)。
10. **能力由开发者 DSL 声明** —— `ModelCapabilities` 给合理默认,开发者只覆盖与默认不同的项(§3.2)。
11. **横切双扩展点防双消费** —— `aroundModelCall` 只选流不消费流,观察走推送式 `AgentListener`,从接口层消灭 cold flow 双订阅(§3.7)。
12. **重试两层不相乘** —— 连接抖动归 Transport 透明重试,会话级重开归 loop `Recovery.Retry`,透传一字节后谁都不重试(§3.4/§4.2)。
13. **多 agent 两级预算** —— per-agent `maxSteps` 看 `localStep`(handoff 重置),整轮 `RunBudget` 看 `globalStep`/`usage`(永不重置)(§4.3.1)。
14. **副作用工具不静默回滚** —— `Tool.hasSideEffects` 标注;整轮回滚带副作用 turn 时显式告警,推荐幂等键/旁路落账/缩小 turn 边界(§4.5)。
15. **Schema 生成器进切片** —— 最小真实生成器(flat+枚举+可空+List+`@SerialName`)纳入垂直切片,不靠手写 schema 掩盖最大风险(§8.1)。
