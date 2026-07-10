@file:OptIn(ExperimentalForeignApi::class)

package org.koaks.cli.tui

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.ECHO
import platform.posix.ICANON
import platform.posix.ISIG
import platform.posix.NCCS
import platform.posix.STDIN_FILENO
import platform.posix.TCSANOW
import platform.posix.VMIN
import platform.posix.VTIME
import platform.posix.getchar
import platform.posix.tcgetattr
import platform.posix.tcsetattr
import platform.posix.termios

internal actual object NativeTerminalInput {
    private var originalMode: SavedTerminalMode? = null

    actual fun enterRawMode(): Boolean = memScoped {
        val mode = alloc<termios>()
        if (tcgetattr(STDIN_FILENO, mode.ptr) != 0) return@memScoped false
        originalMode = SavedTerminalMode(
            inputFlags = mode.c_iflag,
            outputFlags = mode.c_oflag,
            controlFlags = mode.c_cflag,
            localFlags = mode.c_lflag,
            controlCharacters = List(NCCS) { index -> mode.c_cc[index] },
            inputSpeed = mode.c_ispeed,
            outputSpeed = mode.c_ospeed,
        )
        mode.c_lflag = mode.c_lflag and (ECHO or ICANON or ISIG).inv().toULong()
        mode.c_cc[VMIN] = 1u
        mode.c_cc[VTIME] = 0u
        val enabled = tcsetattr(STDIN_FILENO, TCSANOW, mode.ptr) == 0
        if (!enabled) originalMode = null
        enabled
    }

    actual fun leaveRawMode() {
        val saved = originalMode ?: return
        memScoped {
            val mode = alloc<termios>()
            mode.c_iflag = saved.inputFlags
            mode.c_oflag = saved.outputFlags
            mode.c_cflag = saved.controlFlags
            mode.c_lflag = saved.localFlags
            saved.controlCharacters.forEachIndexed { index, value -> mode.c_cc[index] = value }
            mode.c_ispeed = saved.inputSpeed
            mode.c_ospeed = saved.outputSpeed
            tcsetattr(STDIN_FILENO, TCSANOW, mode.ptr)
        }
        originalMode = null
    }

    actual fun readKey(): TerminalKey {
        val value = getchar()
        return when (value) {
            -1, 3, 4 -> TerminalKey.EndOfInput
            8, 127 -> TerminalKey.Backspace
            9 -> TerminalKey.Tab
            10, 13 -> TerminalKey.Enter
            27 -> readEscapeSequence()
            else -> readUtf8(value)
        }
    }

    private fun readEscapeSequence(): TerminalKey {
        if (getchar() != '['.code) return TerminalKey.Escape
        return when (getchar()) {
            'A'.code -> TerminalKey.Up
            'B'.code -> TerminalKey.Down
            'C'.code -> TerminalKey.Right
            'D'.code -> TerminalKey.Left
            'H'.code -> TerminalKey.Home
            'F'.code -> TerminalKey.End
            '3'.code -> if (getchar() == '~'.code) TerminalKey.Delete else TerminalKey.Escape
            else -> TerminalKey.Escape
        }
    }

    private fun readUtf8(first: Int): TerminalKey {
        val byteCount = when {
            first and 0x80 == 0 -> 1
            first and 0xE0 == 0xC0 -> 2
            first and 0xF0 == 0xE0 -> 3
            first and 0xF8 == 0xF0 -> 4
            else -> 1
        }
        val bytes = ByteArray(byteCount)
        bytes[0] = first.toByte()
        for (index in 1 until byteCount) bytes[index] = getchar().toByte()
        return TerminalKey.Text(bytes.decodeToString())
    }
}

private data class SavedTerminalMode(
    val inputFlags: ULong,
    val outputFlags: ULong,
    val controlFlags: ULong,
    val localFlags: ULong,
    val controlCharacters: List<UByte>,
    val inputSpeed: ULong,
    val outputSpeed: ULong,
)
