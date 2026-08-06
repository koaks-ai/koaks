package examples

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlinx.serialization.Serializable
import org.koaks.framework.loop.Agent
import org.koaks.framework.loop.AgentEvent
import org.koaks.framework.loop.AgentResult
import org.koaks.framework.loop.agent
import org.koaks.framework.loop.tool
import org.koaks.framework.memory.ThreadId
import org.koaks.framework.memory.ThreadMemory
import org.koaks.framework.memory.memoryProvider
import org.koaks.framework.model.AgentError
import org.koaks.framework.model.ChatRequest
import org.koaks.framework.model.ContentPart
import org.koaks.framework.model.LanguageModel
import org.koaks.framework.model.Message
import org.koaks.framework.model.ModelCapabilities
import org.koaks.framework.model.ModelEvent
import org.koaks.framework.model.Role
import org.koaks.framework.model.ToolCall
import org.koaks.framework.model.Usage
import org.koaks.memory.summarizing.SummarizingMemory
import org.koaks.runtime.AgentRuntime
import org.koaks.runtime.acb.AcbSnapshot
import org.koaks.runtime.acb.AgentHandle
import org.koaks.runtime.acb.LifecycleState
import org.koaks.runtime.acb.RunId
import org.koaks.runtime.awaitAll
import org.koaks.runtime.context.ContextAccessException
import org.koaks.runtime.context.ContextRef
import org.koaks.runtime.context.ContextScope
import org.koaks.runtime.context.putContext
import org.koaks.runtime.context.resolveContext
import org.koaks.runtime.fault.CircuitBreakerPolicy
import org.koaks.runtime.fault.SupervisionPolicy
import org.koaks.runtime.ipc.RuntimeMessage
import org.koaks.runtime.ipc.receiveMessage
import org.koaks.runtime.ipc.replyMessage
import org.koaks.runtime.ipc.requestMessage
import org.koaks.runtime.observe.RuntimeEvent
import org.koaks.runtime.resource.ChildConversation
import org.koaks.runtime.resource.ChildFailurePolicy
import org.koaks.runtime.resource.currentRuntimeContext
import org.koaks.runtime.resource.quota
import org.koaks.runtime.resource.spawnChild
import org.koaks.runtime.resource.withRuntimeResource
import org.koaks.runtime.sched.taskGraph

fun main(args: Array<String>) = runBlocking {
    Console.colorEnabled = "--no-color" !in args && System.getenv("NO_COLOR") == null
    Console.banner()
    Console.coverageMatrix()

    demonstratePlatformProfile()
    demonstrateSlotParking()
    demonstrateSchedulerAndQuota()

    AgentRuntime {
        maxConcurrency = 2
        defaultQuota = quota {
            maxSteps = 8
            maxToolCalls = 4
            wallClockMillis = 10_000
        }
    }.use { runtime ->
        runtime.withKernelEvents {
            demonstrateContextManagement(runtime)
            demonstrateIpc(runtime)
            demonstrateTaskGraph(runtime)
            demonstrateHandleControl(runtime)
            demonstrateFaultIsolationAndRecovery(runtime)
            demonstrateControlPlane(runtime)
        }
    }

    Console.success("全部演示完成。")
}

private fun demonstratePlatformProfile() {
    Console.section("00", "平台执行面与能力边界", "JVM / Kotlin Multiplatform")

    val osName = System.getProperty("os.name") ?: "unknown"
    val osVersion = System.getProperty("os.version") ?: "unknown"
    val architecture = System.getProperty("os.arch") ?: "unknown"
    val javaVersion = System.getProperty("java.version") ?: "unknown"
    val isLinux = osName.contains("Linux", ignoreCase = true)

    Console.kv("当前 OS", "$osName $osVersion ($architecture)")
    Console.kv("当前 JVM", javaVersion)
    Console.kv("Runtime 实现", "Kotlin 协程 + 纯 Kotlin 调度/IPC/Context/ACB")
    Console.kv("Linux", "core 不依赖 macOS/Windows 专有 Runtime API")
    if (isLinux) {
        Console.success("当前运行环境是 Linux；可继续记录 /etc/os-release 与压力测试数据作为赛题实验材料。")
    } else {
        Console.note("当前主机不是 Linux；Koaks 框架支持 JVM 运行，可在所有支持 JVM 的开源操作系统上运行。")
    }
}

private suspend fun demonstrateSlotParking() {
    Console.section("01", "动态任务生成 + 可让出调度", "工具内 spawnChild；maxConcurrency = 1 仍可完成 parent → child")
    Console.diagram(
        "parent RUNNING [持槽]",
        "parent WAITING [park / 归还槽]",
        "child RUNNING [获得唯一槽]",
        "parent RUNNING [unpark]",
        "DONE",
    )

    val child = fixedAgent(
        id = "slot-child",
        name = "Child / 专项分析",
        delayMillis = 260,
    ) { "子 Agent 已完成专项分析：SlotLease 在等待期间可复用。" }

    val parent = agent {
        id = "slot-parent"
        name = "Parent / 总控"
        instructions = "调用子 Agent，再汇总它的结果。"
        model { custom(ToolThenAnswerModel("delegate", "父 Agent 汇总")) }
        tools {
            tool<ShowcaseNoInput>("delegate", "动态创建并等待子 Agent") {
                val childResult = spawnChild(
                    agent = child,
                    input = "分析等待可让出调度",
                    failurePolicy = ChildFailurePolicy.CAPTURE,
                    conversation = ChildConversation.Inherit,
                ).await()
                childResult.text
            }
        }
        terminateAfter(maxSteps = 4)
    }

    AgentRuntime { maxConcurrency = 1 }.use { runtime ->
        runtime.withKernelEvents {
            val result = runtime.run(parent, "启动父子协作", thread = "slot-parking-demo")
            Console.result("父子协作结果", result.text)
            Console.note("若父 Agent 等待时仍占槽，子 Agent 永远无法启动；本段已在唯一槽位下正常结束。")
            Console.acbTable(runtime.runs)
        }
    }
}

private suspend fun demonstrateSchedulerAndQuota() {
    Console.section("02", "统一调度 + 资源配额", "并发上限 / 优先级准入 / Thread FIFO / 类 cgroup 配额")

    val blockerStarted = CompletableDeferred<Unit>()
    val releaseBlocker = CompletableDeferred<Unit>()
    val admissionOrder = mutableListOf<String>()
    val blocker = agent {
        id = "scheduler-blocker"
        name = "Scheduler Blocker"
        model { custom(GateModel(blockerStarted, releaseBlocker, "blocker released")) }
        terminateAfter(maxSteps = 3)
    }
    fun queuedAgent(id: String, label: String) = agent {
        this.id = id
        name = label
        model { custom(AdmissionProbeModel(label, admissionOrder)) }
        terminateAfter(maxSteps = 3)
    }

    AgentRuntime { maxConcurrency = 1 }.use { runtime ->
        runtime.withKernelEvents {
            val blockerHandle = runtime.spawn(blocker, "占用唯一执行槽", priority = 0)
            blockerStarted.await()
            val low = runtime.spawn(queuedAgent("priority-low", "LOW"), "低优先任务", priority = 1)
            val high = runtime.spawn(queuedAgent("priority-high", "HIGH"), "高优先任务", priority = 9)
            val mid = runtime.spawn(queuedAgent("priority-mid", "MID"), "中优先任务", priority = 5)
            check(awaitState(low, LifecycleState.READY))
            check(awaitState(high, LifecycleState.READY))
            check(awaitState(mid, LifecycleState.READY))
            releaseBlocker.complete(Unit)
            awaitAll(blockerHandle, low, high, mid)

            check(admissionOrder == listOf("HIGH", "MID", "LOW")) { "优先级准入顺序异常：$admissionOrder" }
            Console.result("Priority Scheduler", admissionOrder.joinToString(" → "))
            Console.kv("maxConcurrency", "1（任意时刻仅一个 RUNNING 实例）")

            val firstTurnStarted = CompletableDeferred<Unit>()
            val releaseFirstTurn = CompletableDeferred<Unit>()
            val firstTurn = agent {
                id = "thread-fifo-first"
                name = "Thread Turn #1"
                model { custom(GateModel(firstTurnStarted, releaseFirstTurn, "turn-1")) }
            }
            val secondTurn = fixedAgent("thread-fifo-second", "Thread Turn #2", 30) { "turn-2" }
            val firstHandle = runtime.spawn(firstTurn, "first", thread = "scheduler-thread")
            firstTurnStarted.await()
            val secondHandle = runtime.spawn(secondTurn, "second", thread = "scheduler-thread")
            check(awaitState(secondHandle, LifecycleState.THREAD_QUEUED))
            Console.kv("同 Thread 第二个 Turn", "${secondHandle.state}（FIFO，且不占 Scheduler 槽）")
            releaseFirstTurn.complete(Unit)
            awaitAll(firstHandle, secondHandle)

            val quotaAgent = agent {
                id = "quota-tool-loop"
                name = "Quota Tool Loop"
                model { custom(RepeatingToolModel("consume_resource")) }
                tools {
                    tool<ShowcaseNoInput>("consume_resource", "模拟重复占用模型/工具资源") { "resource-used" }
                }
                terminateAfter(maxSteps = 20)
            }
            val quotaResult = runtime.run(
                quotaAgent,
                "持续调用工具，直到 Runtime 配额终止",
                quota = quota { maxToolCalls = 2; wallClockMillis = 2_000 },
            )
            check(quotaResult is AgentResult.Terminated)
            Console.result("Quota preemption", quotaResult.reason.toString())
            Console.kv("ACB 计数", "toolCalls=${runtime.runs.last().toolCalls}, state=${runtime.runs.last().state}")
        }
    }
}

private suspend fun demonstrateContextManagement(runtime: AgentRuntime) {
    Console.section("03", "上下文复用 / 压缩 / 隔离", "内容寻址 + Copy-on-Write + Summary Memory + PRIVATE ACL")

    val background = (1..200).map { index ->
        Message.system("共享规范 #${index.toString().padStart(3, '0')}：所有模块遵循统一接口、审计和故障边界。")
    }
    val shared = runtime.context.put(background, scope = ContextScope.GLOBAL)
    val deduplicated = runtime.context.put(background, scope = ContextScope.GLOBAL)
    check(shared == deduplicated)
    val agentRefs = (1..10).map { index ->
        runtime.context.delta(
            parent = shared,
            added = listOf(Message.user("Agent-$index 私有任务增量：只处理第 $index 个子域。")),
        )
    }

    val backgroundChars = background.sumOf { it.text.length }
    val deltaChars = agentRefs.sumOf { ref -> runtime.context.get(ref)!!.messages.sumOf { it.text.length } }
    val fullCopyChars = backgroundChars * agentRefs.size + deltaChars
    val sharedChars = backgroundChars + deltaChars
    val ratio = sharedChars.toDouble() / fullCopyChars.toDouble()

    Console.kv("共享根 ContextRef", shared.id)
    Console.kv("内容寻址去重", "相同内容再次 put → 同一 ContextRef=${shared == deduplicated}")
    Console.kv("任一 Agent 的解析视图", "${runtime.context.resolve(agentRefs.first(), requester = null).size} 条消息")
    Console.kv("该 Agent 实际新增块", "${runtime.context.get(agentRefs.first())!!.messages.size} 条消息")
    Console.bar("全量复制", 1.0, "$fullCopyChars chars")
    Console.bar("引用共享", ratio, "$sharedChars chars (${(ratio * 100).toInt()}%)")
    Console.success("结构共享承载量约降低 ${"%.1f".format(fullCopyChars.toDouble() / sharedChars)}×")

    val summarizingMemory = SummarizingMemory(
        maxTokens = 64,
        model = PromptAwareModel(30) { "已压缩：保留架构决策、故障边界与未决问题。" },
        keepRecentTurns = 1,
    )
    val summaryProvider = memoryProvider("showcase-summary-memory") { summarizingMemory }
    val memoryAgent = agent {
        id = "summarizing-memory-agent"
        name = "Summarizing Memory Agent"
        model {
            custom(
                ScriptedUsageModel(
                    replies = listOf("第一轮结论", "第二轮结论", "第三轮结论"),
                    promptTokens = listOf(16, 48, 256),
                ),
            )
        }
        memory { custom(summaryProvider.id, summaryProvider) }
        terminateAfter(maxSteps = 3)
    }
    runtime.run(memoryAgent, "第一轮：确定统一抽象", thread = "summary-context-demo")
    runtime.run(memoryAgent, "第二轮：确定调度策略", thread = "summary-context-demo")
    runtime.run(memoryAgent, "第三轮：确定容错策略", thread = "summary-context-demo")
    val compacted = summarizingMemory.load(Message.user("snapshot"))
    check(compacted.firstOrNull()?.text?.startsWith("Summary of earlier conversation") == true)
    Console.result("摘要压缩", compacted.first().text.substringBefore('\n'))
    Console.kv("压缩后持久上下文", "${compacted.size} 条消息；仅保留最近 ${compacted.count { it.role == Role.USER }} 个原始 Turn")

    var privateRef: ContextRef? = null
    val contextReader = agent {
        id = "private-context-reader"
        name = "Untrusted Child"
        model { custom(ToolThenAnswerModel("read_private_context", "Untrusted Child")) }
        tools {
            tool<ShowcaseNoInput>("read_private_context", "尝试按引用读取另一个 Run 的 PRIVATE 块") {
                try {
                    resolveContext(requireNotNull(privateRef))
                    "denied=false"
                } catch (_: ContextAccessException) {
                    "denied=true"
                }
            }
        }
        terminateAfter(maxSteps = 4)
    }
    val contextOwner = agent {
        id = "private-context-owner"
        name = "Private Context Owner"
        model { custom(ToolThenAnswerModel("verify_context_acl", "Context ACL")) }
        tools {
            tool<ShowcaseNoInput>("verify_context_acl", "创建私有块并让另一个 Agent 尝试读取") {
                val ctx = requireNotNull(currentRuntimeContext())
                val secret = putContext(
                    listOf(Message.system("私有凭据：仅 owner run=${ctx.runId.value} 可读")),
                    scope = ContextScope.PRIVATE,
                )
                privateRef = secret
                val ownerView = resolveContext(secret).single().text
                val childResult = spawnChild(
                    agent = contextReader,
                    input = "收到 ContextRef=${secret.id}，尝试主动解析",
                    failurePolicy = ChildFailurePolicy.CAPTURE,
                    conversation = ChildConversation.Ephemeral,
                ).await()
                "ownerRead=${ownerView.startsWith("私有凭据")}; ${childResult.text}"
            }
        }
        terminateAfter(maxSteps = 4)
    }
    val isolationResult = runtime.run(contextOwner, "验证上下文 ACL")
    Console.result("PRIVATE isolation", isolationResult.text)
}

private suspend fun demonstrateIpc(runtime: AgentRuntime) {
    Console.section("04", "Agent IPC + 统一资源抽象", "Mailbox / Req-Resp / Pub-Sub / ContextRef / ResourceRegistry")

    var reviewerRunId: RunId? = null
    val reviewContext = runtime.context.put(
        (1..80).map { Message.system("架构附件 #$it：调度、上下文、IPC、容错证据。") },
        scope = ContextScope.TASK,
    )
    val reviewer = agent {
        id = "ipc-reviewer"
        name = "Reviewer"
        model { custom(ToolThenAnswerModel("serve_review", "Reviewer")) }
        tools {
            tool<ShowcaseNoInput>("serve_review", "等待审查请求并回复") {
                val request = receiveMessage()
                val ctx = requireNotNull(currentRuntimeContext())
                val attachedMessages = request.contextRefs.sumOf { ref ->
                    ctx.context.resolve(ref, requester = ctx.runId).size
                }
                delay(100)
                replyMessage(request, "审查通过：依赖边完整，失败边界清晰，引用附件=$attachedMessages 条。")
                "已回复 request#${request.id}"
            }
        }
        terminateAfter(maxSteps = 4)
    }
    val requester = agent {
        id = "ipc-requester"
        name = "Architect"
        model { custom(ToolThenAnswerModel("ask_reviewer", "Architect")) }
        tools {
            tool<ShowcaseNoInput>("ask_reviewer", "向 Reviewer 发起带 correlationId 的请求") {
                val reply = requestMessage(
                    to = requireNotNull(reviewerRunId),
                    type = "architecture.review",
                    payload = "请检查 DAG 和容错设计",
                    contextRefs = listOf(reviewContext),
                )
                "收到 reply(correlation=${reply.correlationId})：${reply.payload}"
            }
        }
        terminateAfter(maxSteps = 4)
    }

    val reviewerHandle = runtime.spawn(reviewer, "等待架构审查任务", priority = 5)
    reviewerRunId = reviewerHandle.runId
    delay(80)
    val requesterHandle = runtime.spawn(requester, "发起审查", priority = 6)
    val results = awaitAll(requesterHandle, reviewerHandle)
    Console.result("Request/Response", results.first().text)
    Console.kv("零拷贝式传递", "payload=${"请检查 DAG 和容错设计".length} chars；附件通过 1 个 ContextRef 携带 ${runtime.context.resolve(reviewContext, null).size} 条消息")

    val broadcast = coroutineScope {
        val subscriber = async { runtime.ipc.subscribe("build.status").first() }
        yield()
        runtime.ipc.publish(
            topic = "build.status",
            message = RuntimeMessage(
                id = runtime.ipc.nextId(),
                sender = null,
                receiver = null,
                type = "build.passed",
                payload = "all targets green",
            ),
        )
        subscriber.await()
    }
    Console.result("Pub/Sub", "topic=build.status, type=${broadcast.type}, payload=${broadcast.payload}")

    var ledger = 0
    fun lockedWriter(id: String, label: String) = agent {
        this.id = id
        name = label
        model { custom(ToolThenAnswerModel("write_ledger", label)) }
        tools {
            tool<ShowcaseNoInput>("write_ledger", "写入同一个共享账本") {
                withRuntimeResource("contest-ledger") {
                    val observed = ledger
                    delay(120)
                    ledger = observed + 1
                    "临界区写入完成，ledger=$ledger"
                }
            }
        }
        terminateAfter(maxSteps = 4)
    }
    val writers = awaitAll(
        runtime.spawn(lockedWriter("resource-writer-a", "Writer-A"), "更新共享账本"),
        runtime.spawn(lockedWriter("resource-writer-b", "Writer-B"), "更新共享账本"),
    )
    check(ledger == 2) { "共享资源临界区失效，ledger=$ledger" }
    Console.result("ResourceRegistry", writers.joinToString(" | ") { it.text })
    Console.kv("最终账本值", "$ledger（两个并发写入均未丢失）")
    Console.kv("受管资源", runtime.resources.trackedResources.joinToString())
}

private suspend fun demonstrateTaskGraph(runtime: AgentRuntime) {
    Console.section("05", "复杂多 Agent DAG", "research → { frontend ∥ backend } → summary")
    Console.dagDiagram()

    val researcher = fixedAgent("dag-research", "Researcher", 180) {
        "调研：采用本地优先架构，Runtime 统一管理执行实体。"
    }
    val frontend = fixedAgent("dag-frontend", "Frontend", 280) { input ->
        "前端：状态面板 + ACB 时间线（输入已含调研=${input.contains("调研") }）"
    }
    val backend = fixedAgent("dag-backend", "Backend", 280) { input ->
        "后端：Scheduler + IPC + ContextStore（输入已含调研=${input.contains("调研") }）"
    }
    val summarizer = fixedAgent("dag-summary", "Summarizer", 120) { input ->
        "汇总：前后端方案已合流，依赖结果数=${input.count { it == '：' }.coerceAtLeast(2)}。"
    }

    val graph = taskGraph {
        task("research", researcher, input = "分析 Koaks 作品演示需求", priority = 6)
        task("frontend", frontend, priority = 5, dependsOn = listOf("research")) { deps ->
            "根据调研设计控制台：${deps.getValue("research").text}"
        }
        task("backend", backend, priority = 5, dependsOn = listOf("research")) { deps ->
            "根据调研设计内核：${deps.getValue("research").text}"
        }
        task("summary", summarizer, priority = 7, dependsOn = listOf("frontend", "backend")) { deps ->
            "合并：${deps.getValue("frontend").text} | ${deps.getValue("backend").text}"
        }
    }

    val startedAt = System.nanoTime()
    val results = runtime.submit(graph)
    val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000
    val sequentialBaselineMillis = 180L + 280L + 280L + 120L
    results.forEach { (id, result) -> Console.result(id.padEnd(8), result.text) }
    Console.kv("DAG 总耗时", "${elapsedMillis}ms（frontend/backend 受同一 Scheduler 并行约束）")
    Console.kv("串行延迟基线", "约 ${sequentialBaselineMillis}ms（按各模拟模型延迟求和）")
    Console.kv("并行加速比", "约 ${"%.2f".format(sequentialBaselineMillis.toDouble() / elapsedMillis.coerceAtLeast(1))}×（演示值，正式实验应多轮统计）")
}

private suspend fun demonstrateHandleControl(runtime: AgentRuntime) = coroutineScope {
    Console.section("06", "统一执行抽象与生命周期控制", "Agent/Model/Tool/Resource + spawn/pause/resume/cancel/await")

    val worker = agent {
        id = "controlled-worker"
        name = "Controlled Worker"
        model { custom(ChunkedModel()) }
        terminateAfter(maxSteps = 3)
    }
    val handle = runtime.spawn(worker, "生成一份运行时状态报告")
    val lifecycle = mutableListOf<LifecycleState>()
    val lifecycleCollector = launch {
        handle.updates.collect { snapshot ->
            if (lifecycle.lastOrNull() != snapshot.state) lifecycle += snapshot.state
        }
    }
    delay(130)
    Console.kv("handle", "runId=${handle.runId.value}, state=${handle.state}")

    handle.pause()
    val paused = awaitState(handle, LifecycleState.SUSPENDED)
    Console.kv("pause()", if (paused) "state=${handle.state}，实例已协作暂停" else "请求已发送，实例很快完成")
    delay(160)
    handle.resume()
    val result = handle.await()
    lifecycleCollector.cancelAndJoin()
    Console.result("resume() + await()", result.text)
    Console.kv("ACB 生命周期", lifecycle.joinToString(" → "))

    val streamer = agent {
        id = "streaming-worker"
        name = "Streaming Worker"
        model { custom(ChunkedModel()) }
        terminateAfter(maxSteps = 3)
    }
    var chunkIndex = 0
    runtime.stream(streamer, "流式输出运行时报告").collect { event ->
        if (event is AgentEvent.TextDelta) Console.streamChunk(++chunkIndex, event.text)
    }

    val cancellable = agent {
        id = "operator-cancellable-worker"
        name = "Cancellable Worker"
        model { custom(PromptAwareModel(5_000) { "不应自然完成" }) }
    }
    val cancelled = runtime.spawn(cancellable, "执行一个可由控制面终止的长任务")
    check(awaitState(cancelled, LifecycleState.RUNNING))
    cancelled.cancel("operator requested stop")
    cancelled.join()
    Console.kv("cancel()", "runId=${cancelled.runId.value}, state=${cancelled.state}")
}

private suspend fun demonstrateFaultIsolationAndRecovery(runtime: AgentRuntime) {
    Console.section("07", "故障隔离 / 恢复 / 熔断", "CAPTURE 边界 + Supervisor + checkpoint 输入 + 原子 commit")

    val isolatedFailure = agent {
        id = "isolated-broken-child"
        name = "Broken Specialist"
        model { custom(AlwaysFailModel("模拟单体 Agent 崩溃")) }
    }
    val resilientParent = agent {
        id = "fault-isolation-parent"
        name = "Resilient Coordinator"
        model { custom(ToolThenAnswerModel("delegate_isolated", "Coordinator survived")) }
        tools {
            tool<ShowcaseNoInput>("delegate_isolated", "隔离执行一个可能失败的子 Agent") {
                val childResult = spawnChild(
                    isolatedFailure,
                    "执行高风险专项任务",
                    failurePolicy = ChildFailurePolicy.CAPTURE,
                    conversation = ChildConversation.Ephemeral,
                ).await()
                "childFailed=${childResult is AgentResult.Failed}; failureCaptured=true"
            }
        }
        terminateAfter(maxSteps = 4)
    }
    val isolatedResult = runtime.run(resilientParent, "验证单体故障不会级联")
    check(isolatedResult is AgentResult.Completed)
    Console.result("Failure isolation", isolatedResult.text)

    val memory = RecordingMemory()
    val provider = memoryProvider("showcase-atomic-memory") { memory }
    val flaky = agent {
        id = "supervised-writer"
        name = "Supervised Writer"
        instructions = "生成最终可提交方案。"
        model { custom(FailOnceModel()) }
        memory { custom(provider.id, provider) }
        terminateAfter(maxSteps = 3)
    }

    val result = runtime.spawnSupervised(
        agent = flaky,
        input = "写入比赛演示结论",
        thread = ThreadId("contest-demo"),
        policy = SupervisionPolicy(
            maxRetries = 2,
            initialBackoffMillis = 80,
            backoffFactor = 1.0,
            recover = { attempt, _ -> "从 checkpoint 恢复，第 $attempt 次重试" },
        ),
    ).await()

    val commits = memory.committedTurns()
    val persistedText = commits.flatten().joinToString(" | ") { it.text }
    check(commits.size == 1) { "失败 Turn 不应提交，实际 commits=${commits.size}" }
    check("半成品" !in persistedText) { "失败草稿泄漏进持久记忆" }

    Console.result("监督最终结果", result.text)
    Console.kv("Memory.commit 次数", "${commits.size}（仅成功 Turn）")
    Console.kv("持久化内容检查", "不含失败阶段的‘半成品草稿’")

    val circuitModel = AlwaysFailModel("下游模型持续不可用")
    val circuitAgent = agent {
        id = "circuit-breaker-agent"
        name = "Circuit Protected Model"
        model { custom(circuitModel) }
        terminateAfter(maxSteps = 3)
    }
    val circuitPolicy = SupervisionPolicy(
        maxRetries = 3,
        initialBackoffMillis = 20,
        circuitBreaker = CircuitBreakerPolicy(failureThreshold = 1, resetTimeoutMillis = 5_000),
    )
    val firstFailure = runtime.spawnSupervised(circuitAgent, "第一次访问故障下游", circuitPolicy).await()
    val callsAfterTrip = circuitModel.calls
    val fastFailure = runtime.spawnSupervised(circuitAgent, "熔断期间再次访问", circuitPolicy).await()
    check(firstFailure is AgentResult.Failed && fastFailure is AgentResult.Failed)
    check(circuitModel.calls == callsAfterTrip)
    Console.result("Circuit breaker", "open 后第二次请求快速失败；模型调用数保持 $callsAfterTrip")
}

private suspend fun demonstrateControlPlane(runtime: AgentRuntime) {
    Console.section("08", "系统级可观测控制面", "RuntimeEvent / metrics / ACB snapshot / ThreadSnapshot / reap")

    val metrics = runtime.metrics()
    Console.metric("TOTAL", metrics.total)
    Console.metric("FINISHED", metrics.finished)
    Console.metric("FAILED", metrics.failed)
    Console.metric("CANCELLED", metrics.cancelled)
    Console.metric("TOKENS", metrics.totalTokens)
    Console.metric("STEPS", metrics.totalSteps)
    Console.metric("TOOLS", metrics.totalToolCalls)
    println()
    Console.note("FAILED / CANCELLED 均来自预期的故障注入、熔断或操作员取消；请结合下表右侧说明阅读。")
    Console.acbTable(runtime.runs)

    val thread = runtime.threadSnapshot(ThreadId("contest-demo"))
    Console.kv(
        "ThreadSnapshot",
        "id=${thread?.id?.value}, provider=${thread?.memoryProviderId?.value}, participants=${thread?.participants?.size}",
    )
    val reaped = runtime.reap()
    Console.kv("reap()", "回收 $reaped 个终态 ACB；当前 runs=${runtime.runs.size}")
}

private fun fixedAgent(
    id: String,
    name: String,
    delayMillis: Long,
    reply: (String) -> String,
): Agent = agent {
    this.id = id
    this.name = name
    model { custom(PromptAwareModel(delayMillis, reply)) }
    terminateAfter(maxSteps = 3)
}

private class PromptAwareModel(
    private val delayMillis: Long,
    private val reply: (String) -> String,
) : LanguageModel {
    override val capabilities: ModelCapabilities = ModelCapabilities()

    override fun generate(request: ChatRequest): Flow<ModelEvent> = flow {
        delay(delayMillis)
        val input = request.messages.lastOrNull { it.role == Role.USER }?.text.orEmpty()
        val text = reply(input)
        emit(ModelEvent.TextDelta(text))
        emit(ModelEvent.Completed(usageFor(text)))
    }
}

private class GateModel(
    private val started: CompletableDeferred<Unit>,
    private val release: CompletableDeferred<Unit>,
    private val answer: String,
) : LanguageModel {
    override val capabilities: ModelCapabilities = ModelCapabilities()

    override fun generate(request: ChatRequest): Flow<ModelEvent> = flow {
        started.complete(Unit)
        release.await()
        emit(ModelEvent.TextDelta(answer))
        emit(ModelEvent.Completed(usageFor(answer)))
    }
}

private class AdmissionProbeModel(
    private val label: String,
    private val order: MutableList<String>,
) : LanguageModel {
    override val capabilities: ModelCapabilities = ModelCapabilities()

    override fun generate(request: ChatRequest): Flow<ModelEvent> = flow {
        order += label
        emit(ModelEvent.TextDelta(label))
        emit(ModelEvent.Completed(Usage(promptTokens = 1, completionTokens = 1, totalTokens = 2)))
    }
}

private class RepeatingToolModel(private val toolName: String) : LanguageModel {
    override val capabilities: ModelCapabilities = ModelCapabilities()
    private val calls = AtomicInteger(0)

    override fun generate(request: ChatRequest): Flow<ModelEvent> = flow {
        val call = calls.incrementAndGet()
        emit(ModelEvent.ToolCallCompleted(ToolCall("quota-call-$call", toolName, "{}")))
        emit(ModelEvent.Completed(Usage(promptTokens = 2, completionTokens = 1, totalTokens = 3)))
    }
}

private class ScriptedUsageModel(
    private val replies: List<String>,
    private val promptTokens: List<Int>,
) : LanguageModel {
    override val capabilities: ModelCapabilities = ModelCapabilities()
    private val calls = AtomicInteger(0)

    init {
        require(replies.isNotEmpty() && replies.size == promptTokens.size)
    }

    override fun generate(request: ChatRequest): Flow<ModelEvent> = flow {
        val index = calls.getAndIncrement().coerceAtMost(replies.lastIndex)
        val reply = replies[index]
        val completionTokens = (reply.length / 3).coerceAtLeast(1)
        emit(ModelEvent.TextDelta(reply))
        emit(
            ModelEvent.Completed(
                Usage(
                    promptTokens = promptTokens[index],
                    completionTokens = completionTokens,
                    totalTokens = promptTokens[index] + completionTokens,
                ),
            ),
        )
    }
}

private class AlwaysFailModel(private val message: String) : LanguageModel {
    override val capabilities: ModelCapabilities = ModelCapabilities()
    private val callCounter = AtomicInteger(0)
    val calls: Int get() = callCounter.get()

    override fun generate(request: ChatRequest): Flow<ModelEvent> = flow {
        callCounter.incrementAndGet()
        emit(ModelEvent.Failed(AgentError.ModelError(message, retriable = true)))
    }
}

private class ToolThenAnswerModel(
    private val toolName: String,
    private val answerPrefix: String,
) : LanguageModel {
    override val capabilities: ModelCapabilities = ModelCapabilities()
    private val calls = AtomicInteger(0)

    override fun generate(request: ChatRequest): Flow<ModelEvent> = flow {
        if (calls.getAndIncrement() == 0) {
            emit(ModelEvent.ToolCallCompleted(ToolCall("call-$toolName", toolName, "{}")))
            emit(ModelEvent.Completed(Usage(promptTokens = 6, completionTokens = 2, totalTokens = 8)))
        } else {
            val toolOutput = request.messages
                .lastOrNull { it.role == Role.TOOL }
                ?.parts
                ?.filterIsInstance<ContentPart.ToolResultPart>()
                ?.joinToString("") { it.output }
                .orEmpty()
            val answer = "$answerPrefix：$toolOutput"
            emit(ModelEvent.TextDelta(answer))
            emit(ModelEvent.Completed(usageFor(answer)))
        }
    }
}

private class ChunkedModel : LanguageModel {
    override val capabilities: ModelCapabilities = ModelCapabilities()

    override fun generate(request: ChatRequest): Flow<ModelEvent> = flow {
        val chunks = listOf("调度正常；", "上下文已挂载；", "IPC 在线；", "监督器就绪；", "报告完成。")
        for (chunk in chunks) {
            delay(90)
            emit(ModelEvent.TextDelta(chunk))
        }
        emit(ModelEvent.Completed(Usage(promptTokens = 12, completionTokens = 12, totalTokens = 24)))
    }
}

private class FailOnceModel : LanguageModel {
    override val capabilities: ModelCapabilities = ModelCapabilities()
    private val calls = AtomicInteger(0)

    override fun generate(request: ChatRequest): Flow<ModelEvent> = flow {
        delay(70)
        if (calls.getAndIncrement() == 0) {
            emit(ModelEvent.TextDelta("半成品草稿（该内容必须回滚）"))
            throw IllegalStateException("模拟模型连接中断")
        } else {
            val answer = "恢复成功：TurnCommitBuffer 只提交完整成功回合。"
            emit(ModelEvent.TextDelta(answer))
            emit(ModelEvent.Completed(usageFor(answer)))
        }
    }
}

private class RecordingMemory : ThreadMemory {
    private val mutex = Mutex()
    private val turns = mutableListOf<List<Message>>()

    override suspend fun load(query: Message): List<Message> = mutex.withLock { turns.flatten() }

    override suspend fun commit(messages: List<Message>, usage: Usage) {
        mutex.withLock { turns += messages.toList() }
    }

    suspend fun committedTurns(): List<List<Message>> = mutex.withLock { turns.map { it.toList() } }
}

private fun usageFor(text: String): Usage {
    val completion = (text.length / 3).coerceAtLeast(1)
    return Usage(promptTokens = 8, completionTokens = completion, totalTokens = 8 + completion)
}

private suspend fun awaitState(handle: AgentHandle, state: LifecycleState): Boolean =
    withTimeoutOrNull(1_000) { handle.updates.first { it.state == state } } != null

private suspend fun <T> AgentRuntime.withKernelEvents(block: suspend CoroutineScope.() -> T): T = coroutineScope {
    val collector = launch {
        events.collect { Console.kernelEvent(it) }
    }
    yield()
    try {
        block()
    } finally {
        collector.cancelAndJoin()
    }
}

private object Console {
    var colorEnabled: Boolean = true

    private const val RESET = "\u001B[0m"
    private const val BOLD = "\u001B[1m"
    private const val DIM = "\u001B[2m"
    private const val CYAN = "\u001B[36m"
    private const val BLUE = "\u001B[34m"
    private const val GREEN = "\u001B[32m"
    private const val YELLOW = "\u001B[33m"
    private const val RED = "\u001B[31m"
    private const val MAGENTA = "\u001B[35m"

    fun banner() {
        println(
            cyan(
                """
                ╔══════════════════════════════════════════════════════════════════════╗
                ║  KOAKS · Agent Runtime Capability Showcase                           ║
                ║  像操作进程一样操作 Agent —— 调度 / IPC / Context / Fault / Observe  ║
                ╚══════════════════════════════════════════════════════════════════════╝
                """.trimIndent(),
            ),
        )
        println(dim("  deterministic mock model · real Koaks runtime kernel · no API key required\n"))
    }

    fun coverageMatrix() {
        println(bold("  赛题要求 → 本 Demo 测试项"))
        coverage("已实现", "任务依赖", "TaskGraph DAG")
        coverage("已实现", "动态任务", "tool 内 spawnChild")
        coverage("已实现", "统一调度", "并发槽、优先级、Thread FIFO、等待让槽")
        coverage("已实现", "资源管理", "Quota + ResourceRegistry；硬件负载遥测尚未展示")
        coverage("已实现", "执行抽象", "Agent / RunId / ACB / Handle / 生命周期")
        coverage("已实现", "故障容错", "CAPTURE/PROPAGATE、重试、熔断、原子提交")
        coverage("已实现", "上下文管理", "去重、COW、PRIVATE ACL、摘要压缩")
        coverage("已实现", "Agent 通信", "Mailbox、Req/Resp、Pub/Sub、ContextRef")
        coverage("已实现", "规模压力", "独立 runRuntimeStressTest：默认 10,000 Runs")
        coverage("已实现", "国产 OS", "可在 openEuler/openKylin 等所有支持 JVM 的国产/开源操作系统中运行")
        println()
    }

    private fun coverage(status: String, requirement: String, evidence: String) {
        val marker = when (status) {
            "已实现" -> green("●")
            "可选" -> blue("◇")
            else -> yellow("◆")
        }
        println("  $marker ${requirement.padEnd(10)} ${dim(evidence)}")
    }

    fun section(index: String, title: String, subtitle: String) {
        println()
        println(cyan("┌─[$index] $title ${"─".repeat((48 - title.length).coerceAtLeast(4))}"))
        println(cyan("│ ") + bold(title) + dim("  $subtitle"))
        println(cyan("└${"─".repeat(70)}"))
    }

    fun diagram(vararg nodes: String) {
        println(nodes.joinToString(blue("  →  ")) { bold(it) })
        println()
    }

    fun dagDiagram() {
        println(bold("              ┌→ frontend ─┐"))
        println(bold("  research ───┤             ├→ summary"))
        println(bold("              └→ backend  ─┘"))
        println()
    }

    fun kv(key: String, value: Any?) = println("  ${dim(key.padEnd(22))} ${bold(value.toString())}")

    fun result(label: String, value: String) = println("  ${green("✓")} ${bold(label)}  ${value.replace('\n', ' ')}")

    fun streamChunk(index: Int, value: String) = println("  ${blue("stream[$index]")} $value")

    fun note(value: String) = println("  ${yellow("◆")} ${dim(value)}")

    fun success(value: String) = println("\n${green("✔ $value")}")

    fun bar(label: String, ratio: Double, suffix: String) {
        val width = 36
        val filled = (ratio.coerceIn(0.0, 1.0) * width).toInt().coerceAtLeast(1)
        val bar = "█".repeat(filled) + "░".repeat(width - filled)
        println("  ${label.padEnd(8)} ${cyan(bar)}  $suffix")
    }

    fun metric(label: String, value: Int) {
        print("  ${blue("[$label]")} ${bold(value.toString())}")
    }

    fun kernelEvent(event: RuntimeEvent) {
        val rendered = when (event) {
            is RuntimeEvent.Spawned -> "SPAWN   run=${event.runId.value} agent=${event.agentName} parent=${event.parent?.value ?: "-"}"
            is RuntimeEvent.Running -> "RUN     run=${event.runId.value} agent=${event.agentName}"
            is RuntimeEvent.Waiting -> "PARK    run=${event.runId.value} state=WAITING (slot released)"
            is RuntimeEvent.Suspended -> "PAUSE   run=${event.runId.value} state=SUSPENDED"
            is RuntimeEvent.Resumed -> "RESUME  run=${event.runId.value} state=RUNNING"
            is RuntimeEvent.Finished -> "EXIT    run=${event.runId.value} state=FINISHED tokens=${event.usage.totalTokens}"
            is RuntimeEvent.Terminated -> "STOP    run=${event.runId.value} reason=${event.reason}"
            is RuntimeEvent.Failed -> "FAIL    run=${event.runId.value} error=${event.error.message}"
            is RuntimeEvent.Cancelled -> "CANCEL  run=${event.runId.value}"
            is RuntimeEvent.Retrying -> "RETRY   agent=${event.agentName} attempt=${event.attempt} backoff=${event.delayMillis}ms"
            is RuntimeEvent.CircuitOpen -> "FUSE    agent=${event.agentName} circuit=open"
            is RuntimeEvent.UnhandledChildFailure -> "ORPHAN  child=${event.childRunId.value} error=${event.error.message}"
            is RuntimeEvent.SideEffectRollback -> "ROLLBACK run=${event.runId.value} turn=${event.turnId.value}"
        }
        val color = when (event) {
            is RuntimeEvent.Failed, is RuntimeEvent.Cancelled, is RuntimeEvent.CircuitOpen -> ::red
            is RuntimeEvent.Waiting, is RuntimeEvent.Suspended, is RuntimeEvent.Retrying -> ::yellow
            is RuntimeEvent.Finished -> ::green
            else -> ::magenta
        }
        println("  ${dim("kernel") } ${color(rendered)}")
    }

    fun acbTable(snapshots: List<AcbSnapshot>) {
        println()
        println("  ${bold("RUN")}  ${bold("AGENT".padEnd(22))} ${bold("STATE".padEnd(11))} ${bold("PARENT".padEnd(7))} ${bold("TURN".padEnd(7))} ${bold("TOKENS")}")
        println("  ${dim("─".repeat(64))}")
        snapshots.sortedBy { it.runId.value }.forEach { snapshot ->
            val note = when {
                snapshot.agentName == "Cancellable Worker" && snapshot.state == LifecycleState.CANCELLED ->
                    "  ${yellow("← 刻意取消，验证 Handle.cancel 与控制面终止")}"
                snapshot.agentName == "Broken Specialist" && snapshot.state == LifecycleState.FAILED ->
                    "  ${yellow("← 刻意失败，CAPTURE 隔离；父 Agent 继续运行")}"
                snapshot.agentName == "Supervised Writer" && snapshot.state == LifecycleState.FAILED ->
                    "  ${yellow("← 刻意失败，Supervisor 将重试")}"
                snapshot.agentName == "Supervised Writer" && snapshot.state == LifecycleState.FINISHED ->
                    "  ${green("← Supervisor 重试成功，仅成功 Turn 被提交")}"
                snapshot.agentName == "Circuit Protected Model" && snapshot.state == LifecycleState.FAILED ->
                    "  ${yellow("← 刻意失败并触发熔断；后续请求未再调用模型")}"
                else -> ""
            }
            println(
                "  ${snapshot.runId.value.toString().padEnd(4)} " +
                    "${snapshot.agentName.take(21).padEnd(22)} " +
                    "${stateColor(snapshot.state)(snapshot.state.name.padEnd(11))} " +
                    "${(snapshot.parent?.value?.toString() ?: "-").padEnd(7)} " +
                    "${(snapshot.turnId?.value?.toString() ?: "-").padEnd(7)} " +
                    snapshot.usage.totalTokens + note,
            )
        }
    }

    private fun stateColor(state: LifecycleState): (String) -> String = when (state) {
        LifecycleState.FINISHED -> ::green
        LifecycleState.FAILED, LifecycleState.CANCELLED -> ::red
        LifecycleState.WAITING, LifecycleState.SUSPENDED -> ::yellow
        else -> ::blue
    }

    private fun paint(code: String, value: String): String = if (colorEnabled) "$code$value$RESET" else value
    private fun bold(value: String) = paint(BOLD, value)
    private fun dim(value: String) = paint(DIM, value)
    private fun cyan(value: String) = paint(CYAN, value)
    private fun blue(value: String) = paint(BLUE, value)
    private fun green(value: String) = paint(GREEN, value)
    private fun yellow(value: String) = paint(YELLOW, value)
    private fun red(value: String) = paint(RED, value)
    private fun magenta(value: String) = paint(MAGENTA, value)
}

@Serializable
private data object ShowcaseNoInput
