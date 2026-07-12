@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.koaks.cli.tool

import kotlinx.coroutines.runBlocking
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.remove
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class EditToolTest {
    @Test
    fun replacesUniqueFragment() = runBlocking {
        withTempFile("fun main() {\n    println(\"hello\")\n}\n") { path ->
            val output = EditTool.execute(
                EditInput(path = path, oldString = "\"hello\"", newString = "\"hello, koaks\""),
            )

            assertContains(output, "✓ Edited")
            assertContains(output, "1 replacement")
            assertEquals(
                "fun main() {\n    println(\"hello, koaks\")\n}\n",
                readBack(path),
            )
        }
    }

    @Test
    fun failsWhenFragmentMissing() = runBlocking {
        withTempFile("alpha\nbeta\n") { path ->
            val output = EditTool.execute(
                EditInput(path = path, oldString = "gamma", newString = "delta"),
            )

            assertContains(output, "Error:")
            assertContains(output, "not found")
            assertEquals("alpha\nbeta\n", readBack(path))
        }
    }

    @Test
    fun failsOnAmbiguousMatchWithoutReplaceAll() = runBlocking {
        withTempFile("x = 1\nx = 1\n") { path ->
            val output = EditTool.execute(
                EditInput(path = path, oldString = "x = 1", newString = "x = 2"),
            )

            assertContains(output, "matched 2 locations")
            assertEquals("x = 1\nx = 1\n", readBack(path))
        }
    }

    @Test
    fun replaceAllRewritesEveryOccurrence() = runBlocking {
        withTempFile("x = 1\nx = 1\n") { path ->
            val output = EditTool.execute(
                EditInput(path = path, oldString = "x = 1", newString = "x = 2", replaceAll = true),
            )

            assertContains(output, "2 replacements")
            assertEquals("x = 2\nx = 2\n", readBack(path))
        }
    }

    @Test
    fun preservesCrlfLineEndings() = runBlocking {
        withTempFile("one\r\ntwo\r\nthree\r\n") { path ->
            EditTool.execute(EditInput(path = path, oldString = "two", newString = "TWO"))

            assertEquals("one\r\nTWO\r\nthree\r\n", readBack(path))
        }
    }

    @Test
    fun rejectsIdenticalStrings() = runBlocking {
        withTempFile("same\n") { path ->
            val output = EditTool.execute(
                EditInput(path = path, oldString = "same", newString = "same"),
            )

            assertContains(output, "identical")
        }
    }

    @Test
    fun rejectsEmptyOldString() = runBlocking {
        withTempFile("content\n") { path ->
            val output = EditTool.execute(
                EditInput(path = path, oldString = "", newString = "x"),
            )

            assertContains(output, "oldString is required")
            assertContains(output, "Write tool")
        }
    }
}

class WriteToolTest {
    @Test
    fun createsNewFile() = runBlocking {
        val path = tempPath()
        try {
            val output = WriteTool.execute(WriteInput(path = path, content = "line1\nline2\n"))

            assertContains(output, "✓ Created")
            assertContains(output, "2 lines")
            assertEquals("line1\nline2\n", readBack(path))
        } finally {
            remove(path)
        }
    }

    @Test
    fun overwritesExistingFile() = runBlocking {
        withTempFile("old content\n") { path ->
            val output = WriteTool.execute(WriteInput(path = path, content = "new content\n"))

            assertContains(output, "✓ Overwrote")
            assertEquals("new content\n", readBack(path))
        }
    }

    @Test
    fun requiresPath() = runBlocking {
        val output = WriteTool.execute(WriteInput(path = "  ", content = "x"))
        assertContains(output, "path is required")
    }
}

private fun readBack(path: String): String =
    NativeCliIo.readWholeFile(path, 1_000_000L).text ?: error("unable to read back $path")

private fun tempPath(): String =
    ".koaks-edit-tool-test-${Random.nextInt(0, Int.MAX_VALUE)}.txt"

private suspend fun withTempFile(text: String, block: suspend (String) -> Unit) {
    val path = tempPath()
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
