@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.koaks.cli.tool

import kotlinx.coroutines.runBlocking
import platform.posix.fclose
import platform.posix.fputs
import platform.posix.fopen
import platform.posix.remove
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class ReadToolTest {
    @Test
    fun readsSmallFileWithLineNumbers() = runBlocking {
        withTempTextFile("alpha\nbeta\n") { path ->
            val output = ReadTool.executeForTest(ReadInput(path = path))

            assertContains(output, "1 | alpha")
            assertContains(output, "2 | beta")
        }
    }

    @Test
    fun readsWindowFromOffset() = runBlocking {
        withTempTextFile("one\ntwo\nthree\nfour\n") { path ->
            val output = ReadTool.executeForTest(ReadInput(path = path, offset = 2, limit = 2))

            assertFalse(output.contains("1 | one"))
            assertContains(output, "2 | two")
            assertContains(output, "3 | three")
            assertFalse(output.contains("4 | four"))
        }
    }

    @Test
    fun summarizesLargeFileWithoutExplicitWindow() = runBlocking {
        val text = (1..401).joinToString("\n") { "line-$it" }

        withTempTextFile(text) { path ->
            val output = ReadTool.executeForTest(ReadInput(path = path))

            assertContains(output, "File is too large")
            assertContains(output, "Total lines: 401")
            assertContains(output, "offset=1")
        }
    }
}

private suspend fun ReadTool.executeForTest(input: ReadInput): String = execute(input)

private suspend fun withTempTextFile(text: String, block: suspend (String) -> Unit) {
    val path = ".koaks-read-tool-test-${Random.nextInt(0, Int.MAX_VALUE)}.txt"
    val file = fopen(path, "wb") ?: error("unable to create temp file")
    try {
        fputs(text, file)
    } finally {
        fclose(file)
    }

    try {
        block(path)
    } finally {
        remove(path)
    }
}
