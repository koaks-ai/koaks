package examples

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
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
import org.koaks.framework.model.ModelRequest
import org.koaks.framework.model.LanguageModel
import org.koaks.framework.model.ModelCapabilities
import org.koaks.framework.loop.done
import org.koaks.framework.model.ModelEvent
import org.koaks.framework.model.ModelItem
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
        val executionStartedAt = AtomicLong(0L)
        val progressJob = launch(Dispatchers.Default) {
            var lastSubmitted = -1
            var lastCompleted = -1
            while (true) {
                delay(500)
                val submittedNow = submitted.get()
                val completed = probe.completedCount
                if (completed >= options.agents) break
                if (submittedNow == lastSubmitted && completed == lastCompleted) continue
                lastSubmitted = submittedNow
                lastCompleted = completed
                val executionStart = executionStartedAt.get()
                val executionSeconds = if (executionStart == 0L) {
                    0.0
                } else {
                    (System.nanoTime() - executionStart) / 1_000_000_000.0
                }
                val throughput = if (executionSeconds > 0.0) completed / executionSeconds else 0.0
                StressConsole.progress(
                    submitted = submittedNow,
                    completed = completed,
                    total = options.agents,
                    throughput = throughput,
                )
            }
        }

        val handles = List(options.agents) { index ->
            submittedNanos[index] = System.nanoTime()
            runtime.spawn(worker, "stress-run#$index").also { submitted.incrementAndGet() }
        }
        StressConsole.progress(
            submitted = submitted.get(),
            completed = 0,
            total = options.agents,
            throughput = 0.0,
            forceQueue = true,
        )
        StressConsole.kv("任务提交完毕", "共 ${formatCount(submitted.get())} 个")
        StressConsole.note("开始执行，最多同时运行 ${formatCount(options.concurrency)} 个任务。")
        executionStartedAt.set(System.nanoTime())
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
        val executionElapsedSeconds = (System.nanoTime() - executionStartedAt.get()) / 1_000_000_000.0
        val executionThroughput = options.agents / executionElapsedSeconds.coerceAtLeast(0.000_001)
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
            throughput = executionThroughput,
        )
        StressConsole.kv(
            "测试结果",
            "成功 ${formatCount(completed)}，失败 ${formatCount(failed)}，提前终止 ${formatCount(terminated)}",
        )
        StressConsole.kv("并发控制", "执行峰值 ${probe.maxObservedConcurrency}，配置上限 ${options.concurrency}")
        StressConsole.kv("总耗时", formatMillis(elapsedNanos))
        StressConsole.kv("整体吞吐量", "${"%.0f".format(throughput)} 个任务/秒")
        StressConsole.kv(
            "调度等待时间",
            "50% 的任务不超过 ${percentileMillis(schedulingWaitNanos, 0.50)}ms，" +
                "95% 不超过 ${percentileMillis(schedulingWaitNanos, 0.95)}ms，" +
                "99% 不超过 ${percentileMillis(schedulingWaitNanos, 0.99)}ms",
        )
        StressConsole.kv("内存占用增量", "${formatBytes(heapDelta)}（JVM 估算值）")

        val reapStarted = System.nanoTime()
        val reaped = runtime.reap()
        val reapNanos = System.nanoTime() - reapStarted
        check(reaped == options.agents && runtime.runs.isEmpty())
        StressConsole.result("测试通过", "所有任务均已完成，并发控制符合预期")
        StressConsole.kv("资源回收", "已清理 ${formatCount(reaped)} 条运行记录，用时 ${formatMillis(reapNanos)}")
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

    override fun stream(request: ModelRequest): Flow<ModelEvent> = flow {
        val input = request.items.filterIsInstance<ModelItem.Message>()
            .lastOrNull { it.role == Role.USER }?.text.orEmpty()
        val index = input.substringAfterLast('#').toInt()
        schedulingWaitNanos[index] = System.nanoTime() - submittedNanos[index]

        val current = active.incrementAndGet()
        updateMaximum(current)
        try {
            startGate.await()
            delay(delayMillis)
            emit(ModelEvent.TextDelta("ok"))
            emit(done(Usage.ZERO))
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
        val divider = "━".repeat(64)
        println(cyan(divider))
        println(bold("  KOAKS Agent Runtime 大规模调度压力测试"))
        println("  任务数量：${formatCount(options.agents)}    并发上限：${formatCount(options.concurrency)}")
        println(cyan(divider))
        println(dim("  使用本地模拟模型，无需 API Key\n"))
        note("正在准备 ${formatCount(options.agents)} 个 Agent 任务，请稍候……")
    }

    fun progress(
        submitted: Int,
        completed: Int,
        total: Int,
        throughput: Double,
        forceQueue: Boolean = false,
    ) {
        val queueing = forceQueue || (submitted < total && completed == 0)
        val ratio = if (queueing) submitted.toDouble() / total else completed.toDouble() / total
        val phase = if (queueing) "排队进度" else "执行进度"
        val width = 24
        val filled = (ratio.coerceIn(0.0, 1.0) * width).toInt()
        val bar = "█".repeat(filled) + "░".repeat(width - filled)
        val detail = if (queueing) {
            "已提交 ${formatCount(submitted)} / ${formatCount(total)}"
        } else {
            "已完成 ${formatCount(completed)} / ${formatCount(total)}  执行阶段平均速度 ${"%.0f".format(throughput)} 个/秒"
        }
        println("  ${blue(phase)} ${cyan(bar)} ${"%5.1f".format(ratio * 100)}%  $detail")
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
