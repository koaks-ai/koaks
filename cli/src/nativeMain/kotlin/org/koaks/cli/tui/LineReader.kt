package org.koaks.cli.tui

internal interface LineReader {
    fun readLine(): String?
}

internal object StdinLineReader : LineReader {
    override fun readLine(): String? = readlnOrNull()
}
