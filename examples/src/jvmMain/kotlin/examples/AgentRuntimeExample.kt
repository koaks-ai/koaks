package examples

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.koaks.framework.loop.AgentEvent
import org.koaks.framework.loop.AgentResult
import org.koaks.framework.loop.agent
import org.koaks.framework.model.Message
import org.koaks.provider.openai.openai
import org.koaks.runtime.AgentRuntime
import org.koaks.runtime.awaitAll
import org.koaks.runtime.context.ContextScope
import org.koaks.runtime.observe.RuntimeEvent
import org.koaks.runtime.resource.quota
import org.koaks.runtime.sched.taskGraph
import org.koaks.runtime.spawnIn
import org.koaks.runtime.withAgentRuntime

/**
 * Koaks Runtime 推荐用法（真实模型）。Runtime 已内置于 `koaks-core`。
 *
 * 怎么选：
 * - 单个 Agent、简单对话 → `agent.run()` / `agent.stream()` 使用进程级默认 Runtime
 * - 需要显式生命周期、并发、共享上下文、配额、DAG → 显式创建 [AgentRuntime]
 *
 * 本示例一条流水线：
 * 1. 共享背景进 ContextStore（后面只传 ContextRef）
 * 2. spawn 调研 Agent
 * 3. 前后端并行 spawn + awaitAll
 * 4. 同一套依赖也可用 TaskGraph 表达
 * 5. 看 events / metrics
 *
 * 环境变量（项目根目录 `.env`）：
 * - OPENAI_BASE_URL
 * - OPENAI_API_KEY
 */
fun main() = runBlocking {
    val baseUrl = EnvTools.loadValue("OPENAI_BASE_URL")
    val apiKey = EnvTools.loadValue("OPENAI_API_KEY")
    val modelName = "gpt-5.6-luna"

    // AgentId 标识稳定的 Agent 定义；同一 Agent 可反复运行，每次执行都有独立 RunId。
    fun chatAgent(name: String, instructions: String) = agent {
        id = name
        this.name = name
        this.instructions = instructions
        model {
            openai(baseUrl = baseUrl, apiKey = apiKey, modelName = modelName) {
                temperature = 0.4
            }
        }
        terminateAfter(maxSteps = 8)
    }

    val researcher = chatAgent(
        name = "researcher",
        instructions = "你是产品研究员。根据项目背景，用 3～5 条要点写出实现建议。简洁，不要寒暄。",
    )
    val frontend = chatAgent(
        name = "frontend",
        instructions = "你是前端工程师。根据项目背景和调研结论，列出 UI 组件与页面结构。简洁，不要寒暄。",
    )
    val backend = chatAgent(
        name = "backend",
        instructions = "你是后端工程师。根据项目背景和调研结论，列出 API 端点与数据模型。简洁，不要寒暄。",
    )

    // 显式作用域实例：用完 close（或 .use {}）。直接 Agent API 使用懒加载的默认 Runtime。
    AgentRuntime {
        maxConcurrency = 4
        defaultQuota = quota {
            maxSteps = 8
            wallClockMillis = 120_000
        }
    }.use { rt ->
        // 可选：订阅内核事件（spawn / finish / fail …）
        val eventsJob = launch {
            rt.events.collect { event ->
                when (event) {
                    is RuntimeEvent.Spawned ->
                        println("  [event] spawned ${event.runId} name=${event.agentName}")
                    is RuntimeEvent.Finished ->
                        println("  [event] finished ${event.runId} tokens=${event.usage.totalTokens}")
                    is RuntimeEvent.Failed ->
                        println("  [event] failed ${event.runId}: ${event.error.message}")
                    else -> Unit
                }
            }
        }

        // 1) 共享上下文：大段背景只存一份，spawn 时传 ref，不复制全文。
        val brief = rt.context.put(
            messages = listOf(
                Message.user(
                    """
                    项目：个人待办 App
                    目标用户：个人效率爱好者
                    约束：Web，Kotlin 后端，本地优先，可离线
                    本期范围：任务 CRUD、标签、截止日期；不做协作与通知
                    """.trimIndent(),
                ),
            ),
            scope = ContextScope.GLOBAL,
        )
        println("shared context ref = ${brief.id}")

        // 2) 串行：先调研。spawn 返回 AgentHandle，await() 拿终态结果。
        println("\n== research ==")
        val researchHandle = rt.spawn(
            agent = researcher,
            input = "请基于项目背景给出实现建议。",
            contextRefs = listOf(brief),
        )
        val research = researchHandle.await()
        printResult("researcher", research)

        // CoW delta：只存调研结论增量，父块不复制。
        val withResearch = rt.context.delta(
            parent = brief,
            added = listOf(Message.assistant("调研结论：\n${research.text}")),
        )

        // 3) 并行：前后端同时跑。priority 越高越先拿到并发槽。
        //    也可用扩展函数 agent.spawnIn(rt, ...)。
        println("\n== frontend + backend (parallel) ==")
        val feHandle = rt.spawn(
            agent = frontend,
            input = "请设计前端结构。",
            priority = 5,
            contextRefs = listOf(withResearch),
        )
        val beHandle = backend.spawnIn(
            runtime = rt,
            input = "请设计后端 API。",
            priority = 5,
            contextRefs = listOf(withResearch),
        )
        val (fe, be) = awaitAll(feHandle, beHandle)
        printResult("frontend", fe)
        printResult("backend", be)

        // 4) 依赖关系固定时，也可用 TaskGraph（DAG：依赖就绪才启动）。
        //    这里只展示写法并真正 submit 一条「一句话总结」小图，避免重复打完整流水线。
        println("\n== task graph (tiny) ==")
        val summarizer = chatAgent(
            name = "summarizer",
            instructions = "把用户输入压成一句话中文摘要。不要寒暄。",
        )
        val graph = taskGraph {
            task("summary", summarizer, input = "待办 App：本地优先，任务 CRUD + 标签 + 截止日期。")
        }
        rt.submit(graph).forEach { (id, result) -> printResult("graph/$id", result) }

        // 5) 可观测：聚合指标 + 各实例 ACB 快照。
        println("\n== metrics ==")
        val m = rt.metrics()
        println(
            "  total=${m.total} finished=${m.finished} failed=${m.failed} " +
                "cancelled=${m.cancelled} tokens=${m.totalTokens}",
        )
        rt.runs.forEach { snap ->
            println(
                "  ${snap.runId} name=${snap.agentName} state=${snap.state} " +
                    "steps=${snap.stepsCompleted} tokens=${snap.usage.totalTokens}",
            )
        }

        eventsJob.cancel()
    }

    // 脚本 / 临时作用域的简写：建 + 用 + 关一体。
    // stream：冷流，每次 collect 起一个受 Runtime 管理的实例；停 collect 会协作取消。
    println("\n== withAgentRuntime + stream ==")
    withAgentRuntime(maxConcurrency = 2, defaultQuota = quota { maxSteps = 4 }) {
        val agent = chatAgent(
            name = "one-liner",
            instructions = "用一句话回答。不要寒暄。",
        )
        stream(agent, "用一句话说什么是本地优先的待办 App？").collect { event ->
            when (event) {
                is AgentEvent.TextDelta -> print(event.text)
                is AgentEvent.Completed -> println("\n  [done] tokens=${event.usage.totalTokens}")
                is AgentEvent.Failed -> println("\n  [error] ${event.error.message}")
                else -> Unit
            }
        }
    }
}

private fun printResult(label: String, result: AgentResult) {
    when (result) {
        is AgentResult.Completed -> println("[$label] ${result.text.take(280)}")
        is AgentResult.Terminated -> println("[$label] stopped (${result.reason}): ${result.text.take(280)}")
        is AgentResult.Failed -> println("[$label] failed: ${result.error.message}")
    }
}
