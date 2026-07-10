@file:OptIn(ExperimentalForeignApi::class)

package org.koaks.cli.tui

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.STDERR_FILENO
import platform.posix.STDIN_FILENO
import platform.posix.STDOUT_FILENO
import platform.posix.TIOCGWINSZ
import platform.posix.ioctl
import platform.posix.winsize

internal actual fun nativeTerminalSize(): NativeTerminalSize? =
    readTerminalSize(STDOUT_FILENO)
        ?: readTerminalSize(STDERR_FILENO)
        ?: readTerminalSize(STDIN_FILENO)

private fun readTerminalSize(fd: Int): NativeTerminalSize? = memScoped {
    val size = alloc<winsize>()
    if (ioctl(fd, TIOCGWINSZ, size.ptr) != 0) return@memScoped null

    val rows = size.ws_row.toInt()
    val columns = size.ws_col.toInt()
    if (rows <= 0 || columns <= 0) {
        null
    } else {
        NativeTerminalSize(rows = rows, columns = columns)
    }
}
