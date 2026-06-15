# Koaks Agent 框架重构 — 交接清单

> 本次重构依据 `Koaks Agent 框架重构架构设计方案.md`，将 koaks 从"带工具循环的 Chat Client"重构为通用 Agent 框架。
> **本轮范围**：垂直切片（§8.1）+ §8.2 Phase 1–5（L1 连接层、JSON Schema 生成器、L2 Memory、L3 工具、L4 终止/错误/结构化输出）。**不含 L5**（多 agent/handoff/graph）。
> 彻底重构，不保留向后兼容（§8.3）。详细计划见 `doc/toasty-forging-nova.md`。

---

## 一、已完成（Stage 0 + Stage 1 垂直切片，JVM/JS 双端验证通过）

### 模块/构建
- [x] `llms:qwen` → `koaks-model:qwen`，`llms:ollama` → `koaks-model:ollama`（`git mv`，保留历史）
- [x] `settings.gradle.kts`、`tests/build.gradle.kts` 同步更新；artifactId 改 `koaks-model-*`
- [x] core jvmMain 删除 `reflections`/`kotlin-reflect`（旧反射路径已删）

### koaks-core 新增包（全部 commonMain，零 JVM-only API）
| 包 | 文件 | 状态 |
|---|---|---|
| `model` | Message/ContentPart/Role/ToolCall/Usage/GenerationParams/ChatRequest/ModelEvent/ModelCapabilities/LanguageModel/AgentError | ✅ |
| `transport` | ModelConfig(+StreamFormat/RetryBudget)/WireAdapter/Transport/KtorTransport | ✅ SSE+NDJSON 行模式、连接级重试、AutoCloseable |
| `provider` | WireDecoder/ChatModel | ✅ final generate 走 tee 解码 |
| `tool` | Tool/ToolOutcome/ToolSchema/ToolRegistry/InlineTool | ✅ ToolNotFound 走 Failure、支持运行时追加 |
| `tool.schema` | SerialDescriptorToJsonSchema | ✅ 最小子集：flat/基本类型/枚举/可空/List<基本>/@SerialName |
| `loop` | AgentState/AgentEvent/ToolCallBuilder/TurnAccumulator/AgentRunner/ModelFailure/Agent/AgentResult/AgentDsl/ModelScope/ToolScope/AgentBuilder | ✅ tee 流式、returnDirectly、错误归一、cancel 传播 |
| `policy` | TerminationPolicy/Recovery/ErrorPolicy | ✅ maxSteps/maxTokens/and |
| `middleware` | AgentMiddleware/AgentListener/Tracing | ✅ 双扩展点防双消费 |

### Provider（koaks-model:qwen）
- [x] QwenChatRequest/Response 自包含 wire 类型（OpenAI 兼容）
- [x] QwenWireDecoder 有状态跨 chunk 拼装
- [x] QwenChatModel + `ModelScope.qwen` DSL（含 capabilities 覆盖）

### 删除（§8.3）
- [x] ChatService / ToolManager / ToolInstanceContainer / KoaksContext / AbstractChatModel / ToolCallable / 旧 Tool+createTool / IMemoryStorage 全家 / ModelSelector / 反射 `@Tool` / 旧 entity / ContentListSerializer / 全部旧测试
- [x] **保留**：`mcp/`、`net/`、`utils/`、`model/TypeAdapter`、`platform/`（被保留的 MCP 路径依赖）

### 测试（commonTest，JVM+JS 全绿）
- [x] AgentRunnerTest×4：tee 顺序 / ToolNotFound 失败通道 / 无工具结束 / returnDirectly
- [x] CancellationTest×1：取消传播且不重试
- [x] SerialDescriptorToJsonSchemaTest×5
- [x] QwenWireDecoderTest×2：跨 chunk 拼装 / error→Failed

---

## 二、Stage 2 已完成（§8.2 Phase 1–5，JVM/JS/macosArm 三端编译通过，JVM+JS 测试全绿，45 tests）

### ⚠️ 立即欠债 → 已清
- [x] **Ollama 迁移**：重写为 `OllamaChatModel`+`OllamaWireDecoder`（NDJSON，`StreamFormat.NDJSON`），arguments 为 JSON object、合成 `call_<n>` id；`settings.gradle.kts`/`tests/build.gradle.kts` 已取消注释重新启用

### Phase 1 — 连接层完善
- [x] `RateLimiter`（token-bucket，单调时钟，commonMain 安全）+ `ModelConfig.rateLimit` 接入 KtorTransport
- [x] ModelCapabilities 驱动自适应：`capabilities.tools=false` 时 `Agent.toRequest` 不下发 schema
- [x] 解码器边缘：qwen `finish()` 按 index 排序发 tool call、跳过空累积器；空包/`[DONE]` 已由 transport `parseLine` 覆盖
- [x] MockEngine 测试：透明重试、预算耗尽、**首字节后不重试**（`KtorTransportTest`×3）

### Phase 2 — 完整 JSON Schema 生成器
- [x] 嵌套对象（`$ref`/`$defs` 提升）、sealed/多态（`oneOf`+`type` const 判别符）、Map（`additionalProperties`）、递归（自引用走 `$ref`）、可空 union（`[T,"null"]`/oneOf-with-null）；枚举仍内联
- [x] golden 矩阵扩到 10 tests（含 nested/Map/sealed/recursion/nullable）

### Phase 3 — L2 Memory
- [x] `Memory`/`NoMemory`/`WindowMemory`（turn 原子裁剪 `dropTurnsToFit`，保 assistant↔toolResult 配对，保留前导 system，load 侧裁剪/commit 忠实追加）
- [x] `ThreadId`/`Thread(run/stream)`/`TurnCommitBuffer`（从 AgentEvent 流重建消息，仅 `Finished` 整轮提交，失败/cancel 丢弃 → loop 不碰 Memory）
- [x] 新模块 `koaks-memory:summarizing`（`SummarizingMemory`）/ `:vector`（`VectorMemory`+`VectorStore`）
- [x] 副作用工具回滚 `log.warn`（`Thread.warnOnDiscardedSideEffects` + `ToolRegistry.hasSideEffectingTools`）
- [x] DSL `memory { window(n) / none() / custom(m) }`

### Phase 4 — L3 工具
- [x] `@Tool`/`@Param` JVM 注解 + `annotatedTool<In>`（委托到 `InlineTool`，经 **Java 反射**读注解，无 kotlin-reflect 依赖、无独立反射执行路径）
- [x] MCP 延迟发现：`tools { mcp(gateway) }` → `LazyToolSource`，`ToolRegistry.resolveLazySources()` 首次 run 解析一次并缓存；`Tool.acceptsRawJson`/`parametersOverride` 支持透传工具
- [x] `DefaultMcpClient` 实现 `listTools`/`callTool`（经 `HttpMcpTransport` JSON-RPC），并实现 `McpToolGateway`

### Phase 5 — L4 完善
- [x] 内置 `ErrorPolicy.retryRetriable`/`substituteOnError`（loop catch 块消费 Retry/Substitute/Propagate）
- [x] middleware：`Cache`（短路型 `aroundModelCall`+listener 录制）、`Guardrail`、`HumanApproval`（`aroundToolCall`）
- [x] 结构化输出 `run<T>` + `OutputSpec`（capabilities 驱动 jsonMode-vs-prompt、`JsonExtractor` 围栏剥离/首对象提取、"最后一步才约束格式"）
- [x] `RunBudget`（globalStep/usage 永不重置）接入 loop 最外层条件 + DSL `runBudget(...)`

### 本轮不做（L5）—— 仍未做，留待后续
- handoff / asTool / planner / graph 依赖反转。AgentState 已留 `globalStep`/`localStep`，可非破坏性接入；AgentEvent 待加 `HandoffOccurred`

---

## 三、关键约束（实现 Stage 2 时务必守住，已在切片落地）
1. **tee 流式**：`AgentRunner.kt` collect 内边 emit 边 `acc.observe`，勿退化成先聚合
2. **两层重试不相乘**：Transport 仅首字节前重试；loop Retry 仅在未 emit TextDelta 时
3. **CancellationException 原样上抛**（catch 块第一分支）
4. **ToolNotFound/失败永远走 ToolOutcome.Failure**，不喂假字符串
5. **Agent 持有 Transport 且 AutoCloseable**；custom model 不创建 transport

## 四、构建/验证命令
- **JDK 21 必需**：默认 JDK 25 会让 Gradle 8.14.3 的 Kotlin 编译器抛 `IllegalArgumentException: 25.0.3`。先 `export JAVA_HOME=.../azul-21.0.11/...`
- **代理坑**：macOS 系统代理设在 127.0.0.1:7890（常关）。下载新依赖时追加 `-DsocksProxyHost= -Dsocks.proxyHost= -Dhttp.proxyHost= -Dhttps.proxyHost=` 强制直连
- 快速：`./gradlew :tests:jvmTest`（45 tests 全绿）
- 跨端：`./gradlew :tests:jsNodeTest`（全绿）
- macosArm：三模块 `compileKotlinMacosArm` 通过（目标名是 `macosArm` 非 `macosArm64`）
- 真实 Qwen 集成测试尚未加（EnvTools 已就绪，建议后续加，key 缺失时跳过）
