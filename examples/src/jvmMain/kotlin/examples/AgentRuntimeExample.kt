package examples

import kotlinx.coroutines.runBlocking
import org.koaks.framework.loop.AgentResult
import org.koaks.framework.loop.agent
import org.koaks.framework.model.Message
import org.koaks.provider.openai.openai
import org.koaks.runtime.AgentRuntime
import org.koaks.runtime.awaitAll
import org.koaks.runtime.context.ContextScope
import org.koaks.runtime.resource.quota
import org.koaks.runtime.sched.taskGraph
import org.koaks.runtime.spawnIn
import org.koaks.runtime.withAgentRuntime

/**
 * AgentRuntime 推荐用法。
 *
 * 设计原则：
 * - 单个 Agent、简单对话 → 继续用 `agent.run()` / `agent.stream()`，不必碰 Runtime
 * - 多个 Agent 要并发、共享上下文、配额、DAG → 显式创建 [AgentRuntime]，用 `spawn` 提交
 *
 * 本示例演示一条「产品简报 → 调研 → 前后端并行」流水线：
 * 1. 把项目背景放进 ContextStore（内容寻址，后面只传 ref）
 * 2. spawn 调研 Agent，await 结果
 * 3. 并行 spawn 前端 / 后端（带优先级 + 同一份 contextRefs）
 * 4. 展示 TaskGraph 写法（默认不 submit，避免重复打模型）
 * 5. 看 metrics / ACB 快照；文末附 withAgentRuntime 简写
 *
 * 环境变量（项目根目录 `.env`）：
 * - OPENAI_BASE_URL
 * - OPENAI_API_KEY
 */
fun main() = runBlocking {
    val baseUrl = EnvTools.loadValue("OPENAI_BASE_URL")
    val apiKey = EnvTools.loadValue("OPENAI_API_KEY")
    val modelName = "gpt-5.6-luna"

    // ------------------------------------------------------------------
    // 1) Agent 定义：和不用 Runtime 时完全一样。Runtime 不注册、不拥有 Agent，
    //    只在 spawn 时把 Agent 当参数传进去（同一 Agent 可反复 spawn）。
    // ------------------------------------------------------------------
    val researcher = agent {
        name = "researcher"
        instructions = """
            你是产品研究员。根据项目背景，用 3～5 条要点写出实现建议。
            简洁，不要寒暄。
        """.trimIndent()
        model {
            openai(baseUrl = baseUrl, apiKey = apiKey, modelName = modelName) {
                temperature = 0.5
            }
        }
        terminateAfter(maxSteps = 64)
    }

    val frontendDev = agent {
        name = "frontend"
        instructions = """
            你是前端工程师。根据项目背景和调研结论，列出 UI 组件与页面结构（条目即可）。
            简洁，不要寒暄。
        """.trimIndent()
        model {
            openai(baseUrl = baseUrl, apiKey = apiKey, modelName = modelName) {
                temperature = 0.5
            }
        }
        terminateAfter(maxSteps = 64)
    }

    val backendDev = agent {
        name = "backend"
        instructions = """
            你是后端工程师。根据项目背景和调研结论，列出 API 端点与数据模型（条目即可）。
            简洁，不要寒暄。
        """.trimIndent()
        model {
            openai(baseUrl = baseUrl, apiKey = apiKey, modelName = modelName) {
                temperature = 0.5
            }
        }
        terminateAfter(maxSteps = 64)
    }

    // ------------------------------------------------------------------
    // 2) 显式创建 Runtime：作用域实例，用完 close（或 .use {}）。
    //    没有隐式全局 Runtime —— 想应用级共享就自己在 DI / 顶层持有一个。
    // ------------------------------------------------------------------
    val runtime = AgentRuntime {
        maxConcurrency = 4
        defaultQuota = quota {
            maxSteps = 64
            wallClockMillis = 180_000 // 单实例最长 3 分钟
        }
    }

    runtime.use { rt ->
        // --------------------------------------------------------------
        // 3) 共享上下文：大段背景只存一份，spawn 时传 ContextRef，不复制全文。
        // --------------------------------------------------------------
        val projectBrief = rt.context.put(
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
        println("shared context ref = ${projectBrief.id}")

        // --------------------------------------------------------------
        // 4) 串行：先调研。await() 拿到 AgentResult（Completed / Terminated / Failed）。
        // --------------------------------------------------------------
        println("\n== step A: research ==")
        val researchHandle = rt.spawn(
            agent = researcher,
            input = "请基于项目背景给出实现建议。",
            contextRefs = listOf(projectBrief),
        )
        val research = researchHandle.await()
        printResult("researcher", research)
        println("  acb state=${researchHandle.state} tokens=${researchHandle.snapshot.usage.totalTokens}")

        // 把调研结论再挂一层 delta（CoW：只存增量，父块不复制）。
        val withResearch = rt.context.delta(
            parent = projectBrief,
            added = listOf(Message.assistant("调研结论：\n${research.text}")),
        )

        // --------------------------------------------------------------
        // 5) 并行：前后端同时跑。priority 越高越先拿到并发槽位。
        //    也可用扩展函数：frontendDev.spawnIn(rt, "...", contextRefs = ...)
        // --------------------------------------------------------------
        println("\n== step B: frontend + backend in parallel ==")
        val feHandle = rt.spawn(
            agent = frontendDev,
            input = "请设计前端结构。",
            priority = 5,
            contextRefs = listOf(withResearch),
        )
        val beHandle = backendDev.spawnIn(
            runtime = rt,
            input = "请设计后端 API。",
            priority = 5,
            contextRefs = listOf(withResearch),
        )

        val (fe, be) = awaitAll(feHandle, beHandle)
        printResult("frontend", fe)
        printResult("backend", be)

        // --------------------------------------------------------------
        // 6) 若依赖关系固定，也可用 TaskGraph（DAG：依赖就绪才启动）。
        //    下面只展示写法；取消注释 submit 即可跑通等价流水线。
        //    注意：当前 TaskNode 不接收 contextRefs，共享背景请写进 input，
        //    或继续用上面的 spawn(..., contextRefs = ...) 路径。
        // --------------------------------------------------------------
        val graph = taskGraph {
            task(
                id = "research",
                agent = researcher,
                input = "项目：个人待办 App（Web / Kotlin / 本地优先）。请给出实现建议。",
            )
            task("frontend", frontendDev, dependsOn = listOf("research")) { deps ->
                "调研结论：\n${deps.getValue("research").text}\n请设计前端结构。"
            }
            task("backend", backendDev, dependsOn = listOf("research")) { deps ->
                "调研结论：\n${deps.getValue("research").text}\n请设计后端 API。"
            }
        }
        println("\n== task graph ready: ${graph.nodes.map { it.id }} (submit skipped to save tokens) ==")
        // val graphResults = rt.submit(graph)
        // graphResults.forEach { (id, result) -> printResult("graph/$id", result) }

        // --------------------------------------------------------------
        // 7) 可观测：聚合指标 + 每个实例的 ACB 快照。
        // --------------------------------------------------------------
        println("\n== runtime metrics ==")
        val m = rt.metrics()
        println(
            "  total=${m.total} running=${m.running} finished=${m.finished} " +
                "failed=${m.failed} cancelled=${m.cancelled} tokens=${m.totalTokens}",
        )
        rt.agents.forEach { snap ->
            println(
                "  ${snap.id} name=${snap.agentName} state=${snap.state} " +
                    "steps=${snap.stepsCompleted} tools=${snap.toolCalls} tokens=${snap.usage.totalTokens}",
            )
        }

        // 终态 ACB 会一直留在表里；长跑时可显式回收：
        // println("reaped=${rt.reap()}")
    }

    // ------------------------------------------------------------------
    // 可选写法：withAgentRuntime { } —— 建+关一体，适合脚本 / 临时作用域。
    // ------------------------------------------------------------------
    println("\n== bonus: withAgentRuntime sugar ==")
    withAgentRuntime(maxConcurrency = 2, defaultQuota = quota { maxSteps = 6 }) {
        val h = spawn(
            agent {
                name = "summarizer"
                instructions = "用一句话总结用户输入。不要寒暄。"
                model {
                    openai(baseUrl = baseUrl, apiKey = apiKey, modelName = modelName) {
                        temperature = 0.2
                    }
                }
                terminateAfter(maxSteps = 4)
            },
            input = "待办 App：本地优先，任务 CRUD + 标签 + 截止日期。",
        )
        printResult("summarizer", h.await())
    }
}

/** 打印一次运行结果；不隐藏 AgentResult 的三种终态。 */
private fun printResult(label: String, result: AgentResult) {
    when (result) {
        is AgentResult.Completed -> println("[$label] ok: ${result.text.take(240)}")
        is AgentResult.Terminated -> println("[$label] stopped (${result.reason}): ${result.text.take(240)}")
        is AgentResult.Failed -> println("[$label] failed: ${result.error.message}")
    }
}
