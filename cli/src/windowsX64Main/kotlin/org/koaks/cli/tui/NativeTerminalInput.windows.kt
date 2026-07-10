@file:OptIn(ExperimentalForeignApi::class)

package org.koaks.cli.tui

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix._getwch
import platform.windows.GetConsoleMode
import platform.windows.GetStdHandle
import platform.windows.STD_INPUT_HANDLE

internal actual object NativeTerminalInput {
    actual fun enterRawMode(): Boolean = memScoped {
        val mode = alloc<UIntVar>()
        GetConsoleMode(GetStdHandle(STD_INPUT_HANDLE), mode.ptr) != 0
    }

    actual fun leaveRawMode() = Unit

    actual fun readKey(): TerminalKey {
        val value = _getwch().toInt()
        if (value == 0 || value == 0xE0) return readExtendedKey()
        return when (value) {
            3 -> TerminalKey.EndOfInput
            8 -> TerminalKey.Backspace
            9 -> TerminalKey.Tab
            13 -> TerminalKey.Enter
            26 -> TerminalKey.EndOfInput
            27 -> TerminalKey.Escape
            else -> TerminalKey.Text(value.toChar().toString())
        }
    }

    private fun readExtendedKey(): TerminalKey = when (_getwch().toInt()) {
        71 -> TerminalKey.Home
        72 -> TerminalKey.Up
        75 -> TerminalKey.Left
        77 -> TerminalKey.Right
        79 -> TerminalKey.End
        80 -> TerminalKey.Down
        83 -> TerminalKey.Delete
        else -> TerminalKey.Escape
    }
}
