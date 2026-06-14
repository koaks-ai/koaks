# Koaks Agent 框架重构 — 交接清单

> 本次重构依据 `Koaks Agent 框架重构架构设计方案.md`，将 koaks 从"带工具循环的 Chat Client"重构为通用 Agent 框架。
> **本轮范围**：垂直切片（§8.1）+ §8.2 Phase 1–5（L1 连接层、JSON Schema 生成器、L2 Memory、L3 工具、L4 终止/错误/结构化输出）。**不含 L5**（多 agent/handoff/graph）。
> 彻底重构，不保留向后兼容（§8.3）。详细计划见 `C:\Users\StarFall\.claude\plans\toasty-forging-nova.md`。

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

## 二、剩余（Stage 2，§8.2 Phase 1–5，本轮范围）

### ⚠️ 立即欠债（切片为通过编译临时搁置的）
- [ ] **Ollama 迁移**：现仍是旧 `AbstractChatModel`，已在 `settings.gradle.kts` 和 `tests/build.gradle.kts` **注释禁用**。需改写为 `OllamaChatModel`+`OllamaWireDecoder`（NDJSON，用 `StreamFormat.NDJSON`），完成后取消注释。搜 `TODO(Stage 2`

### Phase 1 — 连接层完善
- [ ] KtorTransport 退避/限流策略完善（RetryBudget 已有骨架，RateLimiter 待加）
- [ ] ModelCapabilities 驱动自适应（jsonMode fallback、并行工具开关）
- [ ] 解码器边缘：多并行工具按 index、空末包、`[DONE]`
- [ ] Ollama 迁移（见上）+ MockEngine 测透明重试 / 首字节后不重试

### Phase 2 — 完整 JSON Schema 生成器
- [ ] 扩展：嵌套对象、sealed/多态(oneOf+判别符)、Map、递归($ref/$defs)、默认值
- [ ] 当前生成器对未覆盖类型是 `error()` 抛出（非静默错误），扩展时替换这些分支

### Phase 3 — L2 Memory
- [ ] Memory 接口 + NoMemory/WindowMemory（turn 原子裁剪，load 侧裁剪/commit 忠实追加）
- [ ] ThreadId/Thread(run/stream)/TurnCommitBuffer（仅 Finished 整轮提交）
- [ ] 新模块 `koaks-memory:summarizing` / `:vector`
- [ ] 副作用工具回滚 `log.warn`（`Tool.hasSideEffects` 字段已预留）

### Phase 4 — L3 工具
- [ ] `@Tool`/`@Param` JVM 语法糖委托到 `Tool<In>`（无独立反射执行路径）
- [ ] MCP 延迟发现：`ToolScope.mcp(url)` 登记 LazyToolSource，首次 run 时 `tools/list` 追加（复用现有 `mcp/client/DefaultMcpClient`，ToolRegistry 已支持 registerAll）

### Phase 5 — L4 完善
- [ ] ErrorPolicy/Recovery 接入已就绪（AgentRunner catch 块已消费 Retry/Substitute/Propagate），待补内置策略 + middleware（RetryOnError/Cache/Guardrail/HumanApproval）
- [ ] 结构化输出 `run<T>` + OutputSpec（jsonMode fallback、围栏剥离、"最后一步才约束格式"）
- [ ] RunBudget（globalStep/usage 永不重置）—— AgentState 字段已预留

### 本轮不做（L5）
- handoff / asTool / planner / graph 依赖反转。AgentState 已留 `globalStep`/`localStep`，可非破坏性接入；AgentEvent 待加 `HandoffOccurred`

---

## 三、关键约束（实现 Stage 2 时务必守住，已在切片落地）
1. **tee 流式**：`AgentRunner.kt` collect 内边 emit 边 `acc.observe`，勿退化成先聚合
2. **两层重试不相乘**：Transport 仅首字节前重试；loop Retry 仅在未 emit TextDelta 时
3. **CancellationException 原样上抛**（catch 块第一分支）
4. **ToolNotFound/失败永远走 ToolOutcome.Failure**，不喂假字符串
5. **Agent 持有 Transport 且 AutoCloseable**；custom model 不创建 transport

## 四、构建/验证命令
- 快速：`./gradlew :tests:jvmTest`
- 跨端：`./gradlew :tests:jsNodeTest`
- macosArm 当前开发机（非 mac）禁用，CI 上需另跑
- 真实 Qwen 集成测试尚未加（EnvTools 已就绪，建议 Phase 1 加，key 缺失时跳过）
