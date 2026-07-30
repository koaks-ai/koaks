package examples

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.koaks.framework.loop.AgentResult
import org.koaks.framework.loop.agent
import org.koaks.framework.model.ChatRequest
import org.koaks.framework.model.LanguageModel
import org.koaks.framework.model.ModelCapabilities
import org.koaks.framework.model.ModelEvent
import org.koaks.framework.model.Role
import org.koaks.framework.model.Usage
import org.koaks.runtime.AgentRuntime
import org.koaks.runtime.awaitAll

fun main(args: Array<String>) = runBlocking {
    val options = parseStressOptions(args)
    StressConsole.colorEnabled = "--no-color" !in args && System.getenv("NO_COLOR") == null
    StressConsole.banner(options)
    runMassSchedulingStress(options)
    StressConsole.success("压力测试完成。")
}

private data class StressOptions(val agents: Int, val concurrency: Int)

private fun parseStressOptions(args: Array<String>): StressOptions {
    fun positiveInt(name: String, default: Int, maximum: Int): Int {
        val raw = args.firstOrNull { it.startsWith("--$name=") }?.substringAfter('=') ?: return default
        val value = raw.toIntOrNull() ?: error("--$name 必须是整数，实际为 '$raw'")
        require(value in 1..maximum) { "--$name 必须在 1..$maximum，实际为 $value" }
        return value
    }

    return StressOptions(
        agents = positiveInt("agents", default = 10_000, maximum = 100_000),
        concurrency = positiveInt("concurrency", default = 128, maximum = 4_096),
    )
}

private suspend fun runMassSchedulingStress(options: StressOptions) = coroutineScope {
    val submittedNanos = LongArray(options.agents)
    val schedulingWaitNanos = LongArray(options.agents)
    val startGate = CompletableDeferred<Unit>()
    val probe = StressProbeModel(submittedNanos, schedulingWaitNanos, startGate, delayMillis = 2)
    val worker = agent {
        id = "mass-scheduling-worker"
        name = "Mass Scheduling Worker"
        model { custom(probe) }
        terminateAfter(maxSteps = 2)
    }

    val heapBefore = usedHeapBytes()
    AgentRuntime { maxConcurrency = options.concurrency }.use { runtime ->
        val startedAt = System.nanoTime()
        val submitted = AtomicInteger(0)
        val progressJob = launch(Dispatchers.Default) {
            var lastSubmitted = -1
            var lastCompleted = -1
            while (true) {
                delay(250)
                val submittedNow = submitted.get()
                val completed = probe.completedCount
                if (completed >= options.agents) break
                if (submittedNow == lastSubmitted && completed == lastCompleted) continue
                lastSubmitted = submittedNow
                lastCompleted = completed
                val elapsedSeconds = (System.nanoTime() - startedAt) / 1_000_000_000.0
                val throughput = completed / elapsedSeconds.coerceAtLeast(0.000_001)
                val remaining = options.agents - completed
                val etaSeconds = if (throughput > 0.0) remaining / throughput else Double.POSITIVE_INFINITY
                StressConsole.progress(
                    submitted = submittedNow,
                    completed = completed,
                    total = options.agents,
                    running = runtime.metrics().running,
                    maximum = probe.maxObservedConcurrency,
                    throughput = throughput,
                    etaSeconds = etaSeconds,
                )
            }
        }

        val handles = List(options.agents) { index ->
            submittedNanos[index] = System.nanoTime()
            runtime.spawn(worker, "stress-run#$index").also { submitted.incrementAndGet() }
        }
        StressConsole.kv("提交完成", "${formatCount(submitted.get())} Runs 已进入 Runtime")
        StressConsole.note("统一起跑门已释放：Scheduler 开始以最多 ${options.concurrency} 个并发槽消费就绪队列。")
        startGate.complete(Unit)

        val heapAfterSpawn = usedHeapBytes()
        val results = handles.awaitAll()
        progressJob.cancelAndJoin()
        val elapsedNanos = System.nanoTime() - startedAt
        val heapAfterRun = usedHeapBytes()

        val completed = results.count { it is AgentResult.Completed }
        val failed = results.count { it is AgentResult.Failed }
        val terminated = results.count { it is AgentResult.Terminated }
        val metrics = runtime.metrics()
        val elapsedSeconds = elapsedNanos / 1_000_000_000.0
        val throughput = options.agents / elapsedSeconds.coerceAtLeast(0.000_001)
        val heapDelta = (maxOf(heapAfterSpawn, heapAfterRun) - heapBefore).coerceAtLeast(0L)

        check(completed == options.agents) {
            "压力测试未全部成功：completed=$completed failed=$failed terminated=$terminated"
        }
        check(probe.maxObservedConcurrency <= options.concurrency) {
            "并发上限失效：observed=${probe.maxObservedConcurrency}, configured=${options.concurrency}"
        }
        check(metrics.total == options.agents && metrics.finished == options.agents)

        StressConsole.progress(
            submitted = submitted.get(),
            completed = completed,
            total = options.agents,
            running = metrics.running,
            maximum = probe.maxObservedConcurrency,
            throughput = throughput,
            etaSeconds = 0.0,
        )
        StressConsole.kv("运行实例", "${formatCount(options.agents)}（同一 Agent 定义，不同 RunId/ACB）")
        StressConsole.kv("完成情况", "completed=${formatCount(completed)}, failed=$failed, terminated=$terminated")
        StressConsole.kv("并发约束", "observed=${probe.maxObservedConcurrency} ≤ configured=${options.concurrency}")
        StressConsole.kv("总耗时", formatMillis(elapsedNanos))
        StressConsole.kv("吞吐量", "${"%.0f".format(throughput)} runs/s")
        StressConsole.kv(
            "调度等待延迟",
            "P50=${percentileMillis(schedulingWaitNanos, 0.50)}ms, " +
                "P95=${percentileMillis(schedulingWaitNanos, 0.95)}ms, " +
                "P99=${percentileMillis(schedulingWaitNanos, 0.99)}ms",
        )
        StressConsole.kv("近似堆内存增量", "${formatBytes(heapDelta)}（未强制 GC，仅作同机对比）")
        StressConsole.kv("Runtime 指标", "runs=${metrics.total}, tokens=${metrics.totalTokens}, steps=${metrics.totalSteps}")

        val reapStarted = System.nanoTime()
        val reaped = runtime.reap()
        val reapNanos = System.nanoTime() - reapStarted
        check(reaped == options.agents && runtime.runs.isEmpty())
        StressConsole.result("稳定性断言", "全部完成、无失败、无越过并发上限、无残留 ACB")
        StressConsole.kv("reap()", "回收 ${formatCount(reaped)} 个终态实例，耗时 ${formatMillis(reapNanos)}")
    }
}

private class StressProbeModel(
    private val submittedNanos: LongArray,
    private val schedulingWaitNanos: LongArray,
    private val startGate: CompletableDeferred<Unit>,
    private val delayMillis: Long,
) : LanguageModel {
    override val capabilities: ModelCapabilities = ModelCapabilities()
    private val active = AtomicInteger(0)
    private val maximum = AtomicInteger(0)
    private val completed = AtomicInteger(0)
    val maxObservedConcurrency: Int get() = maximum.get()
    val completedCount: Int get() = completed.get()

    override fun generate(request: ChatRequest): Flow<ModelEvent> = flow {
        val input = request.messages.lastOrNull { it.role == Role.USER }?.text.orEmpty()
        val index = input.substringAfterLast('#').toInt()
        schedulingWaitNanos[index] = System.nanoTime() - submittedNanos[index]

        val current = active.incrementAndGet()
        updateMaximum(current)
        try {
            startGate.await()
            delay(delayMillis)
            emit(ModelEvent.TextDelta("ok"))
            emit(ModelEvent.Completed(Usage.ZERO))
            completed.incrementAndGet()
        } finally {
            active.decrementAndGet()
        }
    }

    private fun updateMaximum(value: Int) {
        while (true) {
            val observed = maximum.get()
            if (value <= observed || maximum.compareAndSet(observed, value)) return
        }
    }
}

private fun usedHeapBytes(): Long {
    val runtime = Runtime.getRuntime()
    return runtime.totalMemory() - runtime.freeMemory()
}

private fun percentileMillis(samples: LongArray, percentile: Double): String {
    val sorted = samples.sorted()
    val index = (sorted.lastIndex * percentile).toInt().coerceIn(0, sorted.lastIndex)
    return "%.2f".format(sorted[index] / 1_000_000.0)
}

private fun formatMillis(nanos: Long): String = "%.2fms".format(nanos / 1_000_000.0)

private fun formatBytes(bytes: Long): String = "%.2f MiB".format(bytes / (1024.0 * 1024.0))

private fun formatCount(value: Int): String = "%,d".format(value)

private object StressConsole {
    var colorEnabled: Boolean = true

    private const val RESET = "\u001B[0m"
    private const val BOLD = "\u001B[1m"
    private const val DIM = "\u001B[2m"
    private const val CYAN = "\u001B[36m"
    private const val BLUE = "\u001B[34m"
    private const val GREEN = "\u001B[32m"
    private const val YELLOW = "\u001B[33m"

    fun banner(options: StressOptions) {
        val width = 70
        fun line(value: String): String = "║  ${value.padEnd(width - 2)}║"
        println(
            cyan(
                listOf(
                    "╔${"═".repeat(width)}╗",
                    line("KOAKS · Agent Runtime Mass-Scheduling Stress Test"),
                    line("${formatCount(options.agents)} Runs / maxConcurrency=${options.concurrency}"),
                    "╚${"═".repeat(width)}╝",
                ).joinToString("\n"),
            ),
        )
        println(dim("  deterministic mock model · sampled progress · no API key required\n"))
        note("复用同一个不可变 Agent 定义；不订阅逐实例 RuntimeEvent，避免日志干扰测量。")
    }

    fun progress(
        submitted: Int,
        completed: Int,
        total: Int,
        running: Int,
        maximum: Int,
        throughput: Double,
        etaSeconds: Double,
    ) {
        val queueing = submitted < total && completed == 0
        val ratio = if (queueing) submitted.toDouble() / total else completed.toDouble() / total
        val phase = if (queueing) "queue" else "run  "
        val width = 24
        val filled = (ratio.coerceIn(0.0, 1.0) * width).toInt()
        val bar = "█".repeat(filled) + "░".repeat(width - filled)
        val eta = when {
            completed >= total -> "done"
            etaSeconds.isFinite() -> "${"%.1f".format(etaSeconds)}s"
            else -> "--"
        }
        println(
            "  ${blue("stress $phase")} ${cyan(bar)} ${"%5.1f".format(ratio * 100)}%  " +
                "submitted=${formatCount(submitted)}  completed=${formatCount(completed)}  " +
                "running=$running  max=$maximum  rate=${"%.0f".format(throughput)}/s  eta=$eta",
        )
    }

    fun kv(key: String, value: Any?) = println("  ${dim(key.padEnd(22))} ${bold(value.toString())}")
    fun result(label: String, value: String) = println("  ${green("✓")} ${bold(label)}  $value")
    fun note(value: String) = println("  ${yellow("◆")} ${dim(value)}")
    fun success(value: String) = println("\n${green("✔ $value")}")

    private fun paint(code: String, value: String): String = if (colorEnabled) "$code$value$RESET" else value
    private fun bold(value: String) = paint(BOLD, value)
    private fun dim(value: String) = paint(DIM, value)
    private fun cyan(value: String) = paint(CYAN, value)
    private fun blue(value: String) = paint(BLUE, value)
    private fun green(value: String) = paint(GREEN, value)
    private fun yellow(value: String) = paint(YELLOW, value)
}
