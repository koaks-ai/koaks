package org.koaks.test

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestResult

/**
 * 为流式集成测试提供真实时间调度；JVM/Native 使用 runBlocking，JS 使用平台兼容的测试运行器。
 */
internal expect fun runBlockingTest(block: suspend CoroutineScope.() -> Unit): TestResult
