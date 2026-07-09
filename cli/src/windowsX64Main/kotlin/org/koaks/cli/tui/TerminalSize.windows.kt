@file:OptIn(ExperimentalForeignApi::class)

package org.koaks.cli.tui

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.windows.CONSOLE_SCREEN_BUFFER_INFO
import platform.windows.GetConsoleScreenBufferInfo
import platform.windows.GetStdHandle
import platform.windows.STD_ERROR_HANDLE
import platform.windows.STD_OUTPUT_HANDLE

internal actual fun nativeTerminalSize(): NativeTerminalSize? = memScoped {
    readTerminalSize(STD_OUTPUT_HANDLE) ?: readTerminalSize(STD_ERROR_HANDLE)
}

private fun readTerminalSize(stdHandle: UInt): NativeTerminalSize? = memScoped {
    val info = alloc<CONSOLE_SCREEN_BUFFER_INFO>()
    val handle = GetStdHandle(stdHandle)
    if (GetConsoleScreenBufferInfo(handle, info.ptr) == 0) {
        null
    } else {
        val window = info.srWindow
        NativeTerminalSize(
            rows = (window.Bottom - window.Top + 1).toInt(),
            columns = (window.Right - window.Left + 1).toInt(),
        )
    }
}
