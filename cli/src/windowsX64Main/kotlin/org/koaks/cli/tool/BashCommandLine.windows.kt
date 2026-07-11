@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlin.io.encoding.ExperimentalEncodingApi::class,
)

package org.koaks.cli.tool

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.value
import kotlinx.cinterop.wcstr
import kotlin.io.encoding.Base64
import kotlin.math.max
import kotlin.random.Random
import platform.windows.CloseHandle
import platform.windows.CREATE_NO_WINDOW
import platform.windows.CreatePipe
import platform.windows.CreateProcessW
import platform.windows.GetExitCodeProcess
import platform.windows.GetLastError
import platform.windows.HANDLE
import platform.windows.HANDLE_FLAG_INHERIT
import platform.windows.INVALID_HANDLE_VALUE
import platform.windows.PeekNamedPipe
import platform.windows.PROCESS_INFORMATION
import platform.windows.ReadFile
import platform.windows.SECURITY_ATTRIBUTES
import platform.windows.STARTF_USESTDHANDLES
import platform.windows.STARTUPINFOW
import platform.windows.SetHandleInformation
import platform.windows.Sleep
import platform.windows.TRUE
import platform.windows.WAIT_OBJECT_0
import platform.windows.WaitForSingleObject

internal actual object BashCommandLine {
    actual val toolName: String = "PowerShell"
    actual val shellName: String = "PowerShell (`powershell.exe`)"
    actual val commandSyntaxGuidance: String =
        "On Windows, use PowerShell syntax and cmdlets only. " +
            "Do not use Bash syntax or GNU-style options such as `ls -la`, `cat`, `grep`, or `rm -rf`."

    actual fun execute(command: String, maxOutputChars: Int): CommandResult {
        val safeMaxOutputChars = maxOutputChars.coerceAtLeast(0)
        // Stdout is an application protocol. PowerShell host diagnostics stay on the
        // separate stderr pipe and can never become command output after this marker.
        val marker = "KOAKS-RESULT/1 ${Random.nextLong().toULong().toString(16)}"
        val userCommand = Base64.encode(command.encodeToByteArray())
        val script = powerShellScript(marker, userCommand)
        val encodedScript = Base64.encode(script.encodeUtf16LittleEndian())
        val commandLine =
            "powershell.exe -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass " +
                "-EncodedCommand $encodedScript"
        val captureLimit = (
            safeMaxOutputChars.toLong() * MAX_UTF8_BYTES_PER_CHAR + PROTOCOL_HEADROOM_BYTES
            ).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val process = runProcess(commandLine, captureLimit, HOST_ERROR_CAPTURE_BYTES)

        if (process.launchError != null) {
            val message = "Unable to start $shellName: Windows error ${process.launchError}."
            return CommandResult(1, message, message.length, truncated = false)
        }

        val stdout = process.stdout.decodeToString()
        val markerAt = stdout.indexOf(marker)
        if (markerAt < 0) {
            val diagnostic = startupDiagnostic(stdout, process.stderr.decodeToString(), process.status)
            return CommandResult(
                status = process.status,
                output = diagnostic,
                totalOutputChars = diagnostic.length,
                truncated = process.stdoutTruncated || process.stderrTruncated,
            )
        }

        val contentStart = stdout.indexOf('\n', markerAt + marker.length).let { newline ->
            if (newline >= 0) newline + 1 else markerAt + marker.length
        }
        val rawOutput = stdout.substring(contentStart).removePrefix("\r")
        val output = rawOutput.take(safeMaxOutputChars)
        val protocolChars = contentStart.coerceAtMost(process.stdoutTotalBytes)
        val totalOutputChars = if (process.stdoutTruncated) {
            max(output.length, process.stdoutTotalBytes - protocolChars)
        } else {
            rawOutput.length
        }
        return CommandResult(
            status = process.status,
            output = output,
            totalOutputChars = totalOutputChars,
            truncated = process.stdoutTruncated || rawOutput.length > safeMaxOutputChars,
        )
    }
}

private fun powerShellScript(marker: String, encodedUserCommand: String): String =
    """
    ${'$'}ProgressPreference = 'SilentlyContinue'
    ${'$'}koaksUtf8 = New-Object System.Text.UTF8Encoding ${'$'}false
    [Console]::InputEncoding = ${'$'}koaksUtf8
    [Console]::OutputEncoding = ${'$'}koaksUtf8
    ${'$'}OutputEncoding = ${'$'}koaksUtf8
    [Console]::Out.WriteLine('$marker')
    ${'$'}koaksCommandBytes = [Convert]::FromBase64String('$encodedUserCommand')
    ${'$'}koaksCommand = [Text.Encoding]::UTF8.GetString(${'$'}koaksCommandBytes)
    & {
        try {
            ${'$'}koaksScriptBlock = [ScriptBlock]::Create(${'$'}koaksCommand)
            & ${'$'}koaksScriptBlock
            ${'$'}script:koaksSucceeded = ${'$'}?
            ${'$'}script:koaksNativeStatus = ${'$'}global:LASTEXITCODE
        } catch {
            ${'$'}_
            ${'$'}script:koaksSucceeded = ${'$'}false
            ${'$'}script:koaksNativeStatus = ${'$'}null
        }
    } *>&1 | Out-String -Stream | ForEach-Object { [Console]::Out.WriteLine(${'$'}_) }
    if (${'$'}koaksNativeStatus -is [int]) {
        exit ${'$'}koaksNativeStatus
    }
    if (${'$'}koaksSucceeded) {
        exit 0
    }
    exit 1
    """.trimIndent()

/** Runs a child directly through Win32, without `system`, `cmd.exe`, or shell redirection. */
private fun runProcess(
    commandLine: String,
    stdoutCaptureLimit: Int,
    stderrCaptureLimit: Int,
): WindowsProcessOutput = memScoped {
    val security = alloc<SECURITY_ATTRIBUTES>().apply {
        nLength = sizeOf<SECURITY_ATTRIBUTES>().toUInt()
        lpSecurityDescriptor = null
        bInheritHandle = TRUE
    }
    val stdoutRead = alloc<COpaquePointerVar>()
    val stdoutWrite = alloc<COpaquePointerVar>()
    val stderrRead = alloc<COpaquePointerVar>()
    val stderrWrite = alloc<COpaquePointerVar>()
    val stdinRead = alloc<COpaquePointerVar>()
    val stdinWrite = alloc<COpaquePointerVar>()

    val pipeCreationFailed =
        CreatePipe(stdoutRead.ptr, stdoutWrite.ptr, security.ptr, 0u) == 0 ||
        CreatePipe(stderrRead.ptr, stderrWrite.ptr, security.ptr, 0u) == 0 ||
        CreatePipe(stdinRead.ptr, stdinWrite.ptr, security.ptr, 0u) == 0
    if (pipeCreationFailed) {
        val error = GetLastError().toInt()
        closeHandles(stdoutRead.value, stdoutWrite.value, stderrRead.value, stderrWrite.value, stdinRead.value, stdinWrite.value)
        return@memScoped WindowsProcessOutput.launchFailure(error)
    }

    val inheritanceConfigured =
        SetHandleInformation(stdoutRead.value, HANDLE_FLAG_INHERIT.toUInt(), 0u) != 0 &&
            SetHandleInformation(stderrRead.value, HANDLE_FLAG_INHERIT.toUInt(), 0u) != 0 &&
            SetHandleInformation(stdinWrite.value, HANDLE_FLAG_INHERIT.toUInt(), 0u) != 0
    if (!inheritanceConfigured) {
        val error = GetLastError().toInt()
        closeHandles(stdoutRead.value, stdoutWrite.value, stderrRead.value, stderrWrite.value, stdinRead.value, stdinWrite.value)
        return@memScoped WindowsProcessOutput.launchFailure(error)
    }

    val startup = alloc<STARTUPINFOW>().apply {
        cb = sizeOf<STARTUPINFOW>().toUInt()
        dwFlags = STARTF_USESTDHANDLES.toUInt()
        hStdInput = stdinRead.value
        hStdOutput = stdoutWrite.value
        hStdError = stderrWrite.value
    }
    val processInfo = alloc<PROCESS_INFORMATION>()
    val mutableCommandLine = commandLine.wcstr.getPointer(this)
    val created = CreateProcessW(
        lpApplicationName = null,
        lpCommandLine = mutableCommandLine,
        lpProcessAttributes = null,
        lpThreadAttributes = null,
        bInheritHandles = TRUE,
        dwCreationFlags = CREATE_NO_WINDOW.toUInt(),
        lpEnvironment = null,
        lpCurrentDirectory = null,
        lpStartupInfo = startup.ptr,
        lpProcessInformation = processInfo.ptr,
    )
    val createError = if (created == 0) GetLastError().toInt() else null

    CloseHandle(stdoutWrite.value)
    CloseHandle(stderrWrite.value)
    CloseHandle(stdinRead.value)
    CloseHandle(stdinWrite.value)

    if (created == 0) {
        closeHandles(stdoutRead.value, stderrRead.value)
        return@memScoped WindowsProcessOutput.launchFailure(createError ?: 1)
    }

    CloseHandle(processInfo.hThread)
    val stdout = CapturedPipe(stdoutCaptureLimit)
    val stderr = CapturedPipe(stderrCaptureLimit)
    try {
        while (true) {
            val stdoutAvailable = drainAvailable(stdoutRead.value, stdout)
            val stderrAvailable = drainAvailable(stderrRead.value, stderr)
            val exited = WaitForSingleObject(processInfo.hProcess, 0u) == WAIT_OBJECT_0
            if (exited && stdoutAvailable == 0 && stderrAvailable == 0) break
            Sleep(PROCESS_POLL_INTERVAL_MS)
        }

        val exitCode = alloc<UIntVar>()
        GetExitCodeProcess(processInfo.hProcess, exitCode.ptr)
        WindowsProcessOutput(
            status = exitCode.value.toInt(),
            stdout = stdout.bytes(),
            stdoutTotalBytes = stdout.totalBytes,
            stdoutTruncated = stdout.truncated,
            stderr = stderr.bytes(),
            stderrTruncated = stderr.truncated,
        )
    } finally {
        closeHandles(processInfo.hProcess, stdoutRead.value, stderrRead.value)
    }
}

private fun drainAvailable(handle: HANDLE?, captured: CapturedPipe): Int = memScoped {
    if (handle == null || handle == INVALID_HANDLE_VALUE) return@memScoped 0
    val available = alloc<UIntVar>()
    if (PeekNamedPipe(handle, null, 0u, null, available.ptr, null) == 0) return@memScoped 0
    var remaining = available.value.toInt()
    if (remaining <= 0) return@memScoped 0

    val buffer = allocArray<ByteVar>(PIPE_BUFFER_BYTES)
    val bytesRead = alloc<UIntVar>()
    while (remaining > 0) {
        val requested = minOf(remaining, PIPE_BUFFER_BYTES)
        if (ReadFile(handle, buffer, requested.toUInt(), bytesRead.ptr, null) == 0) break
        val count = bytesRead.value.toInt()
        if (count <= 0) break
        captured.append(buffer, count)
        remaining -= count
    }
    available.value.toInt()
}

private class CapturedPipe(private val limit: Int) {
    private val chunks = mutableListOf<ByteArray>()
    private var capturedBytes = 0
    var totalBytes: Int = 0
        private set

    val truncated: Boolean
        get() = totalBytes > capturedBytes

    fun append(buffer: CPointer<ByteVar>, count: Int) {
        totalBytes = (totalBytes.toLong() + count).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val remaining = (limit - capturedBytes).coerceAtLeast(0)
        val take = minOf(count, remaining)
        if (take > 0) {
            chunks += buffer.readBytes(take)
            capturedBytes += take
        }
    }

    fun bytes(): ByteArray {
        val result = ByteArray(capturedBytes)
        var offset = 0
        chunks.forEach { chunk ->
            chunk.copyInto(result, destinationOffset = offset)
            offset += chunk.size
        }
        return result
    }
}

private data class WindowsProcessOutput(
    val status: Int,
    val stdout: ByteArray,
    val stdoutTotalBytes: Int,
    val stdoutTruncated: Boolean,
    val stderr: ByteArray,
    val stderrTruncated: Boolean,
    val launchError: Int? = null,
) {
    companion object {
        fun launchFailure(error: Int): WindowsProcessOutput =
            WindowsProcessOutput(1, byteArrayOf(), 0, false, byteArrayOf(), false, error)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as WindowsProcessOutput

        if (status != other.status) return false
        if (stdoutTotalBytes != other.stdoutTotalBytes) return false
        if (stdoutTruncated != other.stdoutTruncated) return false
        if (stderrTruncated != other.stderrTruncated) return false
        if (launchError != other.launchError) return false
        if (!stdout.contentEquals(other.stdout)) return false
        if (!stderr.contentEquals(other.stderr)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = status
        result = 31 * result + stdoutTotalBytes
        result = 31 * result + stdoutTruncated.hashCode()
        result = 31 * result + stderrTruncated.hashCode()
        result = 31 * result + (launchError ?: 0)
        result = 31 * result + stdout.contentHashCode()
        result = 31 * result + stderr.contentHashCode()
        return result
    }
}

private fun startupDiagnostic(stdout: String, stderr: String, status: Int): String {
    val plainStdout = stdout.trim()
    val plainStderr = stderr.trim()
    val detail = when {
        plainStdout.isNotEmpty() && !plainStdout.containsCliXml() -> plainStdout
        plainStderr.isNotEmpty() && !plainStderr.containsCliXml() -> plainStderr
        else -> "PowerShell emitted an unreadable startup diagnostic."
    }
    return "PowerShell failed before the command protocol started (status=$status). $detail"
}

private fun String.containsCliXml(): Boolean =
    contains("#< CLIXML") || contains("<Objs Version=")

private fun closeHandles(vararg handles: HANDLE?) {
    handles.forEach { handle ->
        if (handle != null && handle != INVALID_HANDLE_VALUE) CloseHandle(handle)
    }
}

private fun String.encodeUtf16LittleEndian(): ByteArray {
    val bytes = ByteArray(length * 2)
    for (index in indices) {
        val code = this[index].code
        bytes[index * 2] = (code and 0xff).toByte()
        bytes[index * 2 + 1] = ((code ushr 8) and 0xff).toByte()
    }
    return bytes
}

private const val MAX_UTF8_BYTES_PER_CHAR = 4
private const val PROTOCOL_HEADROOM_BYTES = 4_096
private const val HOST_ERROR_CAPTURE_BYTES = 16_384
private const val PIPE_BUFFER_BYTES = 8_192
private const val PROCESS_POLL_INTERVAL_MS = 1u
