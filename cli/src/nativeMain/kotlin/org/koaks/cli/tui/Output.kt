@file:OptIn(ExperimentalForeignApi::class)

package org.koaks.cli.tui

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.fflush

/**
 * The sink every renderer writes to. Abstracting stdout behind an interface keeps the
 * `tui` views and [org.koaks.cli.app] free of direct `print`/`fflush` calls, and gives
 * tests a place to capture output.
 */
internal interface Output {
    /** Writes [text] without a trailing newline. */
    fun write(text: String)

    /** Writes [text] followed by a newline. */
    fun writeLine(text: String = "")

    /** Flushes any buffered bytes to the terminal. */
    fun flush()
}

/** The real terminal sink: `print` + libc `fflush`. */
internal class StdoutOutput : Output {
    override fun write(text: String) = print(text)
    override fun writeLine(text: String) = println(text)
    override fun flush() {
        fflush(null)
    }
}
