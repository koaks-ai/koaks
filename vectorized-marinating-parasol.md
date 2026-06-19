# 给 koaks 框架添加 Hook 支持

## Context

框架现有 `AgentMiddleware`(around 风格拦截)+ `AgentListener`(观察)。但 around 的 `next` 在内部从 `state` 现建请求/取原始 call,无法变换"输入",导致两类核心需求做不了:发请求前注入 RAG(改 `ChatRequest`)、工具入参改写/审批(改/拦 `ToolCall`)。

经反复讨论确定终态:**用带类型的 before/after/decision 的 `Hook` 作为唯一拦截原语**(全是值进值出,无 `next` 穿线、无冷流脚枪),它足以覆盖所有实际需求。**短路(cache)被认定为边缘且位置不当的概念——彻底移除**;`AgentMiddleware`(around)失去独占能力,连同 `Cache` 一起删除。缓存若将来需要,以 `LanguageModel` 装饰器实现(不属于拦截层)。`AgentListener`(观察)保持独立不动。工具审批走**进程内**协程挂起(`suspend` + `Deferred`),不引入新原语。

最终拦截层只有两个概念:`Hook`(拦截/变换)+ `AgentListener`(观察)。

## 目标语法

```kotlin
agent {
    hook {
        onModelCall {
            before { ctx -> ctx.request.copy(messages = rag + ctx.request.messages) } // StepContext -> ChatRequest
            after  { ctx, stream -> stream.onEach { metrics.record(ctx.phase, it) } } // 外层非 suspend: Flow -> Flow
        }
        onToolCall {
            before { ctx -> if (approved(ctx.call)) Proceed else Deny("未批准") }     // ToolContext -> ToolDecision
            after  { ctx, outcome -> outcome }                                       // -> ToolOutcome
        }
    }
}
```
`after` 是流变换器,插在 generate 与 loop 的 collect 之间 → 不破坏 tee、不 desync。契约:**绝不 collect**,只用 `map`/`onEach`/`transform`/`catch`/`onCompletion`。模型流 after 不做成 `suspend` 外层函数,避免实现者在返回 Flow 前直接 collect;需要 suspend 工作时放进 Flow 操作符里。注意 `onEach { ... }` / `map { ... }` 的内部 lambda 可以是 suspend action,但它们是惰性的,只会在 AgentRunner collect 这条唯一订阅时执行;被禁止的是在 after 外层直接 `stream.collect { ... }` 后再返回。跨 before/after 的状态(计时等)不能简单放 Hook 实例字段,因为同一个 Agent/Hook 可被并发 run 复用,工具调用也会并行执行;状态应放在局部 Flow 闭包、线程安全结构,或按 run/step/call id 建 key。

## 改动清单

### 1. 新增 Hook 原语
新文件 `core/.../middleware/Hook.kt`(承载从 `AgentMiddleware.kt` 迁来的上下文类型)
```kotlin
enum class ModelCallPhase { Normal, StructuredFinalization }

data class StepContext(
    val state: AgentState,
    val request: ChatRequest,
    val phase: ModelCallPhase = ModelCallPhase.Normal,
)

// 保持现有 ToolContext(call, state) 构造顺序,降低破坏面;DSL 内按 ctx.call/ctx.state 使用。
data class ToolContext(val call: ToolCall, val state: AgentState)

interface Hook {
    suspend fun onModelRequest(ctx: StepContext): ChatRequest = ctx.request
    fun onModelStream(ctx: StepContext, events: Flow<ModelEvent>): Flow<ModelEvent> = events
    suspend fun onToolCall(ctx: ToolContext): ToolDecision = ToolDecision.Proceed
    suspend fun onToolResult(ctx: ToolContext, outcome: ToolOutcome): ToolOutcome = outcome
}
```
新文件 `core/.../middleware/ToolDecision.kt`
```kotlin
sealed interface ToolDecision {
    data object Proceed : ToolDecision
    data class ProceedWith(val call: ToolCall) : ToolDecision   // 改入参;必须保留原 call.id
    data class Deny(val reason: String) : ToolDecision          // 拒绝(审批/规则)
}
```

### 2. 删除 around 层
- 删 `core/.../middleware/AgentMiddleware.kt`(`StepContext`/`ToolContext` 迁至 `Hook.kt`)。
- 删 `core/.../middleware/Cache.kt`。

### 3. 内置扩展改写为 Hook
- `Guardrail.kt`:实现 `Hook`,逻辑搬到 `onToolCall(ctx)`:`check(ctx)` 非空 → `Deny(reason)`,否则 `Proceed`。构造器 `(check: (ToolContext) -> String?)` 不变。
- `HumanApproval.kt`:实现 `Hook`,`onToolCall(ctx)`:`guard(ctx) && !approve(ctx)` → `Deny(...)`,否则 `Proceed`。`approve` 仍 `suspend`,天然支持进程内审批挂起。构造器签名不变。
- `Tracing.kt`:仅更新文档注释里对 `aroundModelCall` 的提及;它是 `AgentListener`,功能不变。

### 4. 重写 AgentRunner 的模型步与工具步
`core/.../loop/AgentRunner.kt`(替换当前 79-83 模型步、146-156 工具步,删除两处 `foldRight` 与相关 import)
- 模型步:
  ```
  var req = agent.toRequest(state)
  for (h in agent.hooks) req = h.onModelRequest(StepContext(state, req, ModelCallPhase.Normal))   // 安装序
  var source = agent.model.generate(req)
  for (h in agent.hooks.asReversed()) source = h.onModelStream(StepContext(state, req, ModelCallPhase.Normal), source) // 反序(洋葱)
  source.collect { ... }   // tee/累积/错误处理不变
  ```
- 工具步(每个 `async` 内):
  ```
  var current = call; var denied: ToolOutcome? = null
  for (h in agent.hooks) when (val d = h.onToolCall(ToolContext(current, state))) {
      Proceed -> {}
      is ProceedWith -> current = d.call.copy(id = call.id) // 工具结果必须关联原始 tool_call_id
      is Deny -> { denied = ToolOutcome.Failure(AgentError.ToolError(current.name, d.reason, false)); break }
  }
  var outcome = denied ?: agent.tools.call(current.name, current.arguments)
  for (h in agent.hooks.asReversed()) outcome = h.onToolResult(ToolContext(current, state), outcome)
  ```
- `runStructured` 的 finalization 请求(当前 238 行)同样过一遍 `onModelRequest`/`onModelStream`,但 `StepContext.phase = ModelCallPhase.StructuredFinalization`,让 RAG 注入、流改写、审计等 Hook 可按阶段选择跳过或改变行为,避免污染最终 JSON。
- 因为 `StepContext` 需要 `AgentState`,而当前 `runLoop` 只返回 `List<Message>`,实现时优先把 `runLoop` 返回值改成包含最终 `AgentState` 的内部结果类型(如 `LoopRun(state: AgentState)`),`runStructured` 从最终 state 构造 finalization request;这样 phase、usage、step 等上下文不会丢。

### 5. hook { } DSL
新文件 `core/.../loop/HookDSL.kt`(同 `AgentBuilder` 包,`@AgentDSL`)
- `HookScope`:`onModelCall { }`、`onToolCall { }`。
- `ModelCallHookScope`:`before(suspend (StepContext) -> ChatRequest)`、`after((StepContext, Flow<ModelEvent>) -> Flow<ModelEvent>)`。注意 `after` 外层不是 suspend;Flow 操作符内部的 suspend action 是允许的。
- `ToolCallHookScope`:`before(suspend (ToolContext) -> ToolDecision)`、`after(suspend (ToolContext, ToolOutcome) -> ToolOutcome)`。
- 同一个 `onModelCall`/`onToolCall` 里允许注册多个 `before`/`after`,按声明顺序执行;build 出来的 Hook 内部维护列表,避免后一次声明静默覆盖前一次。
- `HookScope.build(): Hook`:
  - `onModelRequest(ctx)` 按序把 `ctx.request` 传给每个 model-before。每一步生成新 `StepContext(state = ctx.state, request = current, phase = ctx.phase)`。
  - `onModelStream(ctx,e)` 按声明顺序包装流;外层多个 Hook 仍由 AgentRunner 的 `hooks.asReversed()` 提供全局洋葱顺序。不 collect。
  - `onToolCall(ctx)` 按序执行 tool-before,遇到 `Deny` 立即返回;遇到 `ProceedWith` 更新 `current = decision.call.copy(id = ctx.call.id)`,后续 before 看到更新后的 call。
  - `onToolResult(ctx,o)` 按序执行 tool-after。
- 为让 `Proceed`/`Deny` 等在 DSL 内可直接书写,`ToolCallHookScope` 重导出 `ToolDecision` 成员(或文档提示 import)。

### 6. 装配
- `loop/Agent.kt`:`middlewares: List<AgentMiddleware>` → `hooks: List<Hook>`;改 import。
- `loop/AgentBuilder.kt`:`middlewares` 列表 → `hooks`;删 `install(AgentMiddleware)`;加 `fun install(hook: Hook)` 与 `fun hook(block: HookScope.() -> Unit) { hooks += HookScope().apply(block).build() }`;保留 `install(AgentListener)`;`build()` 传 `hooks`。

### 7. 测试
- `tests/.../loop/L4Test.kt`:删除 `cache_short_circuits_second_identical_run`(Cache 已移除)及 `Cache` import;`guardrail_blocks_tool_call` 改用 `install(Guardrail{...})`(现为 Hook,`install(Hook)` 重载),错误文案断言按实际实现调整。
- `tests/.../loop/FakeLanguageModel.kt`:加 `var lastRequest: ChatRequest? = null`(`generate` 内记录),供断言 before 注入。
- 新文件 `tests/.../loop/HookTest.kt`:
  - `onModelCall_before_injects_request`:`model.lastRequest` 含注入消息,且 `state` 不被污染。
  - `onModelCall_after_wraps_stream`:`after` 用 `map` 改写 TextDelta,最终文本被变换。
  - `onModelCall_structured_finalization_sets_phase`:`run<T>` 的最终格式化请求带 `StructuredFinalization` phase,Hook 可选择跳过 RAG/文本改写。
  - `onToolCall_before_deny_blocks`:`Deny` → tool body 未跑,`ToolResult.isError`。
  - `onToolCall_before_rewrites_arguments_preserves_id`:`ProceedWith` 可改 name/arguments,但返回给模型的 tool result 仍使用原始 call id。
  - `onToolCall_before_suspends_until_approved`:`before` 内 `await` 一个 `CompletableDeferred`,另一协程 `complete(true)` 后放行(进程内挂起/恢复)。
  - `onToolResult_transforms_outcome`:`after` 截断 Success 输出。

### 8. 示例
新文件 `examples/src/jvmMain/kotlin/examples/Hooks.kt`:一个 agent 演示 `onModelCall.before`(RAG 注入)+ `onToolCall.before`(`Deferred` 进程内审批)+ `onToolCall.after`(结果截断),作对外文档样板。

### 9. 文档
- 更新 `README.md` / `README-zh.md`:删除 `Cache`、`AgentMiddleware` 的公开说明,改为 `hook { ... }` + `install(Guardrail/HumanApproval/Tracing)` 示例。
- 视情况更新 `doc/HANDOFF.md` / `doc/toasty-forging-nova.md`:说明当前扩展点已从 middleware 迁移到 Hook + AgentListener。

## 复用与一致性
- 复用 `ToolOutcome.Failure` + `AgentError.ToolError`(显式失败通道)、tee/冷流契约。
- Hook 是 AgentRunner 唯一直接消费的拦截原语;AgentListener 仍独立做观察。

## 破坏性说明
删除 `AgentMiddleware`、`Cache` 及短路能力。`Guardrail`/`HumanApproval` 由 middleware 变为 Hook(对外构造器用法不变)。`Deny` 的错误文案以实际实现为准,相关测试同步调整,不要求保留旧字符串断言。所有引用点(`Agent.kt`、`AgentBuilder.kt`、`AgentRunner.kt`、`L4Test.kt`、README/文档)随本次一并适配。

## 验证
- 构建须用 **JDK 21**(memory `build-jdk-requirement`),非默认 JDK 25。
- `./gradlew :tests:allTests`(或 jvmTest)全绿:HookTest 通过,`L4Test` 调整后不回归。
- 运行 examples 的 `Hooks.kt` main,肉眼确认 RAG 注入与审批挂起/放行。
