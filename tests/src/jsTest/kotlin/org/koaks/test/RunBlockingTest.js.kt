package org.koaks.test

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest

internal actual fun runBlockingTest(block: suspend CoroutineScope.() -> Unit): TestResult =
    runTest { block() }
