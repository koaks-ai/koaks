package org.koaks.cli.tui

internal data class NativeTerminalSize(
    val rows: Int,
    val columns: Int,
)

internal expect fun nativeTerminalSize(): NativeTerminalSize?
