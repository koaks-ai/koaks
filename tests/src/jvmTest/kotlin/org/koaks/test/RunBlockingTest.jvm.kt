package org.koaks.test

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestResult

internal actual fun runBlockingTest(block: suspend CoroutineScope.() -> Unit): TestResult =
    runBlocking(block = block)
