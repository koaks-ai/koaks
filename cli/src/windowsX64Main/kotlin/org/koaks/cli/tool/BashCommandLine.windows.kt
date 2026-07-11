@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package org.koaks.cli.tool

import kotlin.io.encoding.Base64

internal actual object BashCommandLine {
    actual val shellName: String = "PowerShell (`powershell.exe`)"
    actual val commandSyntaxGuidance: String =
        "On Windows, use PowerShell syntax and cmdlets only. " +
            "Do not use Bash syntax or GNU-style options such as `ls -la`, `cat`, `grep`, or `rm -rf`."

    actual fun build(command: String, outputPath: String): String {
        val encodedCommand = Base64.encode(powerShellScript(command).encodeUtf16LittleEndian())
        return "powershell.exe -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass " +
            "-EncodedCommand $encodedCommand > ${cmdQuote(outputPath)} 2>&1"
    }
}

private fun cmdQuote(value: String): String =
    "\"" + value
        .replace("%", "%%")
        .replace("\"", "\\\"") + "\""

private fun powerShellScript(command: String): String =
    """
    ${'$'}ProgressPreference = 'SilentlyContinue'
    ${'$'}koaksUtf8 = New-Object System.Text.UTF8Encoding ${'$'}false
    [Console]::InputEncoding = ${'$'}koaksUtf8
    [Console]::OutputEncoding = ${'$'}koaksUtf8
    ${'$'}OutputEncoding = ${'$'}koaksUtf8
    & {
    $command
    ${'$'}script:koaksSucceeded = ${'$'}?
    ${'$'}script:koaksNativeStatus = ${'$'}global:LASTEXITCODE
    } *>&1 | Out-String -Stream
    if (${'$'}koaksNativeStatus -is [int]) {
        exit ${'$'}koaksNativeStatus
    }
    if (${'$'}koaksSucceeded) {
        exit 0
    }
    exit 1
    """.trimIndent()

private fun String.encodeUtf16LittleEndian(): ByteArray {
    val bytes = ByteArray(length * 2)
    for (index in indices) {
        val code = this[index].code
        bytes[index * 2] = (code and 0xff).toByte()
        bytes[index * 2 + 1] = ((code ushr 8) and 0xff).toByte()
    }
    return bytes
}
