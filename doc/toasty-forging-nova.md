# Koaks → Agent 框架重构实施计划

## Context（背景）

当前 `koaks` 本质是"带 tool-call 循环的 Chat Client"，存在四类结构性问题（设计文档 §1.1）：全局可变单例（`ToolManager`/`KoaksContext`/`ToolInstanceContainer`）、God Class（`ChatService`）、无 Agent 一等公民、工具循环僵硬（带工具强制关流式、`returnDirectly` 是 TODO、工具找不到时把 `"Tool X not found"` 当结果喂回模型）。

本次按设计文档（`Koaks Agent 框架重构架构设计方案.md`）做**彻底重构，不保留向后兼容**（§8.3），目标是把它变成通用 Agent 框架。

**本轮范围**（用户确认）：先做垂直切片（§8.1）验证架构，再完成 §8.2 的 Phase 1–5（L1 连接层、JSON Schema 生成器、L2 Memory、L3 工具、L4 终止/错误/结构化输出）。**不含 L5**（多 agent / handoff / graph 重构）。`graph/` 模块本轮完全不动。

**预期结果**：一个 `agent {}` DSL 构建的 agent 能边流式输出 token 边调用工具并继续，工具错误走显式通道，工具 schema 由真实生成器产出。

---

## 关键设计约束（必须保留的正确性点，来自 §4）

实现时这几点最易写错，是验收红线：

1. **Tee 流式**：model step 必须边 `emit(AgentEvent.TextDelta)` 边旁路累积（`acc.observe`），**绝不**先 `collectTurn()` 收完再 `emitAll`。下游要在工具调用前就先收到文本 token。
2. **跨 chunk 拼装 ToolCall**：`WireDecoder` 有状态，跨多个 chunk 拼 `name`/`arguments`，组装完整才 emit `ToolCallCompleted`。
3. **ToolNotFound 走显式 Failure**：`ToolRegistry.call` 找不到工具返回 `ToolOutcome.Failure(ToolNotFound)`，loop 转成 `AgentEvent.Failed` / 带 `isError` 的 tool message，**绝不**伪造字符串喂回模型（复刻旧 bug = 切片失败）。
4. **CancellationException 原样上抛**：loop catch 块中 `is CancellationException -> throw t`，先于任何错误映射。
5. **ModelFailure 内部载体**：`ModelEvent.Failed` 已携带 `AgentError`，用 `internal class ModelFailure` 跳出 collect 后直接取出，不做 `AgentError→Throwable→AgentError` 往返。
6. **两层重试不相乘**：Transport 只重试连接级/首字节前失败；loop `Recovery.Retry` 是会话级，且只在本轮尚未 emit 任何 `TextDelta` 时安全。透传一字节后谁都不重试。
7. **Agent 持有 Transport 且 AutoCloseable**：`agent {}` 构造时创建并持有 Transport，`close()` 关闭它；外部注入的 Transport 不 close。提供 `use {}`。

---

## 目标包结构（全部在 `koaks-core`，pkg `org.koaks.framework`）

- `model` — Message / ContentPart / Role / ToolCall / Usage / ChatRequest / ModelEvent / LanguageModel / ModelCapabilities / AgentError
- `transport` — Transport / WireAdapter / ModelConfig / KtorTransport
- `provider` — ChatModel<TReq,TResp> / WireDecoder
- `tool` — Tool<In> / ToolRegistry / ToolOutcome / ToolSchema；生成器在 `tool.schema`
- `loop` — AgentRunner / AgentState / AgentEvent / TurnAccumulator / ToolCallBuilder / Agent / AgentBuilder + scopes / ModelFailure(internal)
- `memory` — Memory / NoMemory / WindowMemory / ThreadId / Thread / TurnCommitBuffer
- `middleware` — Hook / ToolDecision / AgentListener / Tracing
- `policy` — TerminationPolicy / RunBudget / ErrorPolicy / Recovery

provider 实现移到 `koaks-model:qwen` / `koaks-model:ollama`（包名保持 `org.koaks.provider.*`）。

---

## Stage 0 — 模块/构建重排（最先做）

逐步重命名模块、清理 settings，**不删旧 core 源码**，保证每步可解析。

1. `git mv llms/qwen koaks-model/qwen`、`git mv llms/ollama koaks-model/ollama`，删除空的 `llms/` 目录。`graph/` 不动。
2. `settings.gradle.kts`：`include("llms:qwen")`/`include("llms:ollama")` → `include("koaks-model:qwen")`/`include("koaks-model:ollama")`。确认无 `agent-framework` include（已无）。
3. `tests/build.gradle.kts`：`project(":llms:qwen")` → `project(":koaks-model:qwen")`，ollama 同理。
4. provider 的 `project(":core")` 依赖不变（core 仍是 `:core`）。可选：`artifactId` 改 `koaks-model-qwen`（纯外观）。
5. `./gradlew projects`（只读）验证模块树解析。**不做完整 build**（旧测试仍引用待删 API）。

**KMP 注意点**：
- 约定插件按 `target.name == "tests"` 配 JS 测试，provider 改名不影响 `tests`，JS harness 安全。
- 每 target 引擎依赖（okhttp/js/darwin）留在 core，provider 经 `api(...)` 传递继承，**provider 不碰 Ktor**。
- `koaks-model/` 中间段不需要 build 文件。
- core jvmMain 的 `reflections`/`kotlin-reflect` 只服务旧反射工具路径，**等 Stage 1 删掉该代码后再删依赖**，否则编译断。

---

## Stage 1 — 垂直切片（§8.1）

打通 `LanguageModel(流式) → 作用域 ToolRegistry → 强类型 loop → Tracing`。删旧 API、建新类型、改写 qwen provider、改写测试，作为**一个原子阶段**完成，中途不做完整 build。

按依赖顺序构建：

### 1a. 统一模型（pkg `model`）
- `Message.kt` — `Message(role, parts, id?)` + 工厂 `assistant/user/tool/system`
- `ContentPart.kt` — sealed：`Text`/`Image`/`Audio`/`ToolCallPart`/`ToolResultPart`
- `Role.kt`、`ToolCall.kt`（`id,name,arguments:String`）、`Usage.kt`（+ `ZERO`）
- `ChatRequest.kt`、`ModelEvent.kt`（`TextDelta`/`ToolCallDelta`/`ToolCallCompleted`/`Completed`/`Failed`）
- `ModelCapabilities.kt`（文档 §3.2 默认值）、`LanguageModel.kt`、`AgentError.kt`（sealed：`ModelError`/`ToolError`/`ParseError`/`ToolNotFound`/`Timeout`）

### 1b. Transport（pkg `transport`）
- `ModelConfig.kt`、`WireAdapter.kt`（`KSerializer` 对）
- `Transport.kt`（`stream(...): Flow<TResp>`，`AutoCloseable`）
- `KtorTransport.kt` — 持有单个 Ktor `HttpClient`（复用 `net.provideEngine()`），移植 `KtorHttpClient.postAsStringStream` 的 SSE 行读取；连接级透明重试；`close()` 关 client。**行解码做成模式开关**（SSE `data:` vs NDJSON），为 ollama 预留。

### 1c. Provider 抽象（pkg `provider`）
- `WireDecoder.kt`（`accept(chunk):List<ModelEvent>` + `finish()`）
- `ChatModel.kt` — `abstract class ChatModel<TReq,TResp>(config, transport):LanguageModel`，`final generate()` 按 chunk `decoder.accept().forEach{emit}` 再 `finish().forEach{emit}`

### 1d. 工具系统（pkg `tool`）
- `Tool.kt`（`name/description/inputSerializer/returnDirectly=false/hasSideEffects=false/execute:String`）
- `ToolOutcome.kt`（`Success(output,returnDirectly)`/`Failure(error)`）、`ToolSchema.kt`
- `ToolRegistry.kt` — **实例非 object**；`register` 局部唯一 `require`；`toSchemas()`；`call(name,argsJson)` 找不到返回 `Failure(ToolNotFound)`；**支持运行时追加**（为后续 MCP 预留）

### 1e. 最小 JSON Schema 生成器（pkg `tool.schema`）— 必须进切片
- `SerialDescriptorToJsonSchema.kt` — `SerialDescriptor → JsonObject`。切片**只覆盖**：flat data class、基本类型（String/Int/Boolean/Double/Long/Float）、枚举（→ `enum` 名）、可空（不进 `required`）、`List<基本类型>`（→ `array`/`items`）、`@SerialName`。**暂不覆盖**：嵌套对象、sealed/多态、泛型、递归、`Map`、contextual（留 Phase 2）。
- 这是整个重构最大正确性风险且是工具调用地基，故拉进切片用真实生成器，**不手写 schema**。

### 1f. Loop（pkg `loop`）
- `AgentState.kt` — 强类型 data class（**非 `Map<String,Any>`**）：messages/globalStep/localStep/usage/activeAgentName + `append`/`appendToolResults`/`toRequest`/`lastAssistantOrEmpty`。（localStep/globalStep 本轮虽不用于 handoff，但预留字段，L5 可非破坏性接入。）
- `AgentEvent.kt` — sealed：`TextDelta`/`ToolCallRequested`/`ToolResult(callId,output,isError)`/`StepCompleted`/`Finished`/`Failed`（`HandoffOccurred` 延后到 L5）
- `ToolCallBuilder.kt` — 按 id/index 合并 `ToolCallDelta` 分片 + `ToolCallCompleted`，`build():ToolCall`
- `TurnAccumulator.kt` — `observe(event)` 累积 text/toolCalls/usage；`assistantMessage()`/`toolCalls()`/`usage()`
- `AgentRunner.kt` — `stream(initial):Flow<AgentEvent>` 实现 tee：`source.collect { acc.observe(it); when(it){ TextDelta->emit(...); ToolCallCompleted->emit(ToolCallRequested); Failed->throw ModelFailure(it.error) } }`。collect 后 append assistant + `StepCompleted`；无 calls → `Finished`；工具步 `coroutineScope{ calls.map{async{...}}.awaitAll() }`，逐个 emit outcome，再查 `returnDirectly` → `Finished`。catch 块按约束 #4/#5/#6 处理。`run()=stream().toResult()`
- `ModelFailure.kt` — `internal class ModelFailure(val error)`，loop 内部载体
- `Agent.kt` — 持有 name/instructions/model/tools/hooks/listeners/termination/transport；`AutoCloseable`（关自有 Transport，跳过外部注入）
- `AgentBuilder.kt` + scopes — `@DslMarker annotation class AgentDsl`；`AgentBuilder`/`ModelScope`/`ToolScope`；顶层 `inline fun agent(block)`；`ToolScope.tool(Tool)` 与 `inline fun <reified In> ToolScope.tool(name,description,execute)` 用 `serializer<In>()`

### 1g. 终止（pkg `policy`）
- `TerminationPolicy.kt` — `fun interface shouldStop(state)`；companion `maxSteps`/`maxTokens`/`and`。切片用 `maxSteps`

### 1h. Hook/Listener（pkg `middleware`）
- `Hook.kt` / `ToolDecision.kt` — typed before/after/decision：模型请求/流变换、工具调用改写/拒绝、工具结果变换。模型流 hook 只返回惰性 Flow 包装，**绝不 collect**。
- `AgentListener.kt` — 推送式 `onModelEvent`/`onAgentEvent`/`onStep`，由 loop 在 tee 单点触发（接口层根除 cold flow 双订阅）
- `Tracing.kt` — 实现为 `AgentListener`（观察型）

### 1i. Qwen provider（在 `koaks-model:qwen`）打通端到端
- 新 `QwenChatModel : ChatModel<QwenChatRequest,QwenChatResponse>` 实现 `toWire`/`adapter`/`capabilities`/`newDecoder()`
- `QwenWireDecoder` — 有状态：累积 `Delta.content`→`TextDelta`；按 `index`/`id` 拼装分片 `tool_calls[].function.name`/`.arguments`，在 `finish_reason=="tool_calls"` 或流结束 emit `ToolCallCompleted`；`usage`→`Completed`；`error`→`Failed`。复用现有 `QwenChatRequest`/`QwenChatResponse` 序列化形状
- 改写 `QwenSelector.kt` 为 `fun ModelScope.qwen(modelName,block)`，脱离旧 `ModelSelector`

### Stage 1 删除清单（§8.3，与建新类型同阶段进行）
删：`service/ChatService.kt`；`toolcall/ToolManager.kt`、`caller/{AnnoTypeExecutor,OuterExecutor,ToolExecutor}.kt`（+ js/jvm/macos actuals）、`toolcall/{ToolInitializer,ToolType,ToolInterfaceExt}.kt`、`executor/*`；`context/KoaksContext.kt`；`model/{AbstractChatModel,TypeAdapter,ToolCallable}.kt`；`memory/{IMemoryStorage,DefaultMemoryStorage,NoneMemoryStorage}.kt`；`api/dsl/ModelSelector.kt` 与旧 chat-client builders；旧 `toolcall/toolinterface/{Tool,ToolCreater,NoneInput}.kt`；`Koaks.kt`；jvm `annotation/{Tool,Param}.kt`。保留/转化：`net/*`（移植入 transport 后删）、`utils/json/JsonUtil`、`entity/*` 多模态类型按需重映射。之后从 core jvmMain 删 `reflections`/`kotlin-reflect`。
旧测试 `TestChatClient*`/`TestToolInterface*`/`TestHttpClient*`/`annotools/*`/`implTools/*` 全部删除或改写（引用已删 API，否则阻塞编译）。保留 `EnvTools` 和 graph 测试（`EmailGraph`/`TestInterceptor`，graph 不动）。

---

## Stage 2 — §8.2 Phase 1–5（依赖顺序）

### Phase 1 — 连接层完善（`transport`/`provider`/providers）
`KtorTransport` 重试+退避+限流（`RetryBudget`/`RateLimiter`）；`ModelCapabilities` 驱动自适应（并行工具、jsonMode fallback）；解码器边缘情况（多并行工具按 index、空末包、`[DONE]`）。**迁移 ollama** 到新 `ChatModel`（`OllamaChatModel`+`OllamaWireDecoder`；ollama 是 **NDJSON 非 SSE**，靠 KtorTransport 的行模式开关）。

### Phase 2 — 完整 JSON Schema 生成器（`tool.schema`）
扩展到嵌套对象、sealed/多态（oneOf+判别符）、`List`/`Map`、可空、默认值、`@SerialName`、递归（`$ref`/`$defs`）。补 schema 测试矩阵。

### Phase 3 — L2 Memory（`memory` + 新模块 `koaks-memory:*`）
`Memory` 接口；core 内 `NoMemory`/`WindowMemory`（**turn 原子裁剪**，`dropTurnsToFit` 保 assistant↔toolResult 配对，**只在 load 侧**裁剪，commit 忠实追加）；`ThreadId`/`Thread(agent,id)` 的 `run`/`stream`；`TurnCommitBuffer`（仅在 `Finished` 整轮提交，失败/cancel 丢弃）。新模块 `koaks-memory:summarizing`（`SummarizingMemory`，需 `LanguageModel`）、`koaks-memory:vector`（`VectorMemory`/`VectorStore`），加入 settings。数据流严格按 §4.5：loop 不碰 Memory，load/commit 在 run 边界。副作用工具（`hasSideEffects=true`）参与的回滚轮 `log.warn`。

### Phase 4 — L3 工具（`tool` + jvm `annotation`）
`@Tool`/`@Param` 降级为编译/委托到 `Tool<In>` 的 JVM 语法糖（无独立反射执行路径）。MCP 延迟发现：`mcp(url)` 在 `ToolScope` 登记 `LazyToolSource`，`AgentRunner` 首次 run 在 suspend 上下文 `tools/list` 并追加进 `ToolRegistry`（缓存一次）。复用现有 `mcp/client/DefaultMcpClient`。

### Phase 5 — L4 完善（`policy`/`middleware`/`loop`）
`TerminationPolicy.and`/`maxTokens`；`ErrorPolicy`+`Recovery`（`Propagate`/`Retry`/`Substitute`）在 loop catch 块消费（§4.2）；结构化输出 `run<T>` + `OutputSpec`，capabilities 驱动 jsonMode-vs-prompt fallback、容错解析（剥 ```` ```json ```` 围栏）、"工具跑完最后一步才约束格式"策略；Hook 生态（模型请求/流变换、`Guardrail`/`HumanApproval`、工具结果变换）；`RunBudget`（globalStep/usage 永不重置）作为外层护栏。

> 本轮**不含 L5**：跳过 `asTool`/`handoffs`/planner 与 graph 依赖反转。`AgentState` 已预留 `globalStep`/`localStep`，L5 可后续非破坏性接入。

---

## 验证策略（按阶段，结合 KMP 测试设置）

构建矩阵 jvm + js(IR nodejs) + macosArm。`tests` 模块有 commonTest/jvmTest/jsTest/macosArmTest；`EnvTools`（expect/actual）经 dotenv 在 jvm 读 API key。快速迭代用 `./gradlew :tests:jvmTest`，阶段收尾跑各 target。

- **Stage 0**：`./gradlew projects` 解析重命名后模块树。不编译待删 API 测试。
- **Stage 1**（核心，纯 common 单测，无网络，跑全 target）：
  - schema 生成器 golden 测试（flat/枚举/可空/List/@SerialName）
  - `WireDecoder` 喂罐装 Qwen SSE chunk，断言跨 chunk tool-call 拼装
  - `AgentRunner` 用 fake `LanguageModel`（脚本化 `Flow<ModelEvent>`）断言：(a) `TextDelta` 在 `ToolCallRequested` **之前** emit（tee）；(b) `ToolNotFound`→`AgentEvent.Failed`/isError；(c) `CancellationException` 传播且不触发 retry
  - 一个 jvmTest 真实 Qwen 集成测试（key=="null" 时跳过）验端到端流式+工具+tracing
- **Phase 1**：解码器边缘（并行工具、ollama NDJSON）；`MockEngine` 测 transport 透明重试次数 + "首字节后不重试"
- **Phase 2**：schema golden 矩阵扩到 sealed/Map/递归，跑全 target
- **Phase 3**：in-memory store 测 turn 原子裁剪保配对、仅 Finished 提交、失败丢弃、副作用告警；`Thread` 多轮历史流
- **Phase 4**：注解→Tool 委托（jvmTest）；MCP 延迟发现用 fake transport（commonTest）
- **Phase 5**：ErrorPolicy/Recovery（Retry 仅 pre-TextDelta/Substitute/Propagate）；结构化输出解析（围栏剥离/jsonMode fallback）；`RunBudget` vs per-agent 终止；`Agent.close()` 关自有/不关注入（fake closeable Transport）

---

## 风险排序

1. **JSON Schema 生成器**：最大正确性风险，工具调用地基。故拉进切片（1e）用最小真实子集，不手写。
2. **Tee 流式回退**：最易悄悄退化成 aggregate-then-emit。切片"token 先于 toolcall"顺序断言是守门。
3. **两层重试相乘**：`ModelError.retriable=true` 严格限于首字节前。
4. **Stage 1 build 断裂窗口**：删 core API + 建新类型 + 改 provider + 改测试，作为一个原子阶段，中途不完整 build。
5. **per-target source set**：schema/decoder/loop/model 全在 commonMain 零 JVM-only API（无反射）。仅 `@Tool` 注解（Phase 4）和 EnvTools 是 JVM-bound。`reflections`/`kotlin-reflect` 在删反射路径后才删。
6. **ollama NDJSON ≠ SSE**：KtorTransport 行解码需格式模式开关，不硬编码 `data:`。

## 关键文件
- `settings.gradle.kts`、`core/build.gradle.kts`、`tests/build.gradle.kts`
- `core/.../net/KtorHttpClient.kt`（SSE 读取移植源）、`net/HttpClientConfig.kt`（`streamEndMarker="[DONE]"`）
- `koaks-model/qwen/.../QwenChatResponse.kt`（已含 Delta + tool_calls[].index + 分片 arguments，WireDecoder 原料）
