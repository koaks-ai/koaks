@file:OptIn(ExperimentalForeignApi::class)

package org.koaks.cli.tui

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.isatty

/**
 * Terminal capability probes: TTY detection and window size. This is the seam over
 * `posix`/Win32; higher layers ask [Terminal] instead of calling libc directly.
 */
internal object Terminal {

    /** The current window size, or null if it can't be determined (e.g. piped output). */
    fun size(): NativeTerminalSize? = nativeTerminalSize()

    /** Whether stdin is an interactive terminal (fd 0). */
    fun stdinIsTty(): Boolean = isatty(0) == 1

    /** Whether stdout is an interactive terminal (fd 1). */
    fun stdoutIsTty(): Boolean = isatty(1) == 1
}
