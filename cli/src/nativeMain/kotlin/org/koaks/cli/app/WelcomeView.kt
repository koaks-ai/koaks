package org.koaks.cli.app

import org.koaks.cli.config.AgentConfig
import org.koaks.cli.tui.Ansi
import org.koaks.cli.tui.Output
import org.koaks.cli.tui.TextUtil
import org.koaks.cli.tui.Theme

internal object WelcomeView {

    private val LOGO_SHADOW_GLYPHS = setOf('╔', '╗', '╚', '╝', '═', '║')

    private val PIXEL_LOGO_SHADOW = listOf(
        "██╗   ██╗   ███████╗    ██████╗   ██╗   ██╗  ████████╗",
        "██║  ██╔╝  ██╔════██╗  ██╔═══██╗  ██║  ██╔╝  ██╔═════╝",
        "█████╔╝    ██║    ██║  ████████║  █████╔╝    ████████╗",
        "██╔══██╗   ██║    ██║  ██╔═══██║  ██╔══██╗   ╚═════██║",
        "██║   ██╗  ╚███████╔╝  ██║   ██║  ██║   ██╗  ████████║",
        "╚═╝   ╚═╝   ╚══════╝   ╚═╝   ╚═╝  ╚═╝   ╚═╝  ╚═══════╝",
    )

    private val PIXEL_LOGO = listOf(
        "██    ██   ███████     ██████    ██    ██   ████████ ",
        "██   ██   ██     ██   ██    ██   ██   ██    ██       ",
        "█████     ██     ██   ████████   █████      ████████ ",
        "██   ██   ██     ██   ██    ██   ██   ██          ██ ",
        "██    ██   ███████    ██    ██   ██    ██   ████████ ",
    )

    fun render(config: AgentConfig, output: Output, theme: Theme, clearScreen: Boolean) {
        if (clearScreen && theme.enabled) {
            output.write("${Ansi.CLEAR_SCREEN}${Ansi.HOME}")
        }

        output.writeLine(panelLine('┌', '┐'))
        PIXEL_LOGO_SHADOW.forEach { line ->
            output.writeLine(panelRow(styleLogoShadow(line, theme)))
        }
        output.writeLine(panelLine('├', '┤'))
        output.writeLine(panelRow("${theme.label("Provider")} ${config.provider.id}  ${theme.label("Model")} ${config.modelName}"))
        output.writeLine(panelRow("${theme.label("Thread")} ${config.threadId}  ${theme.label("History")} ${config.historyMessages} messages"))
        output.writeLine(panelRow(theme.dim("Type /help for commands. Type /exit, /quit, or :q to leave.")))
        output.writeLine(panelLine('└', '┘'))
    }

    private fun styleLogoShadow(line: String, theme: Theme): String {
        if (!theme.enabled) return line

        return buildString {
            var index = 0
            while (index < line.length) {
                val shadow = line[index] in LOGO_SHADOW_GLYPHS
                val start = index
                while (index < line.length && (line[index] in LOGO_SHADOW_GLYPHS) == shadow) {
                    index += 1
                }

                val segment = line.substring(start, index)
                append(if (shadow) theme.dim(segment) else segment)
            }
        }
    }

    private fun panelLine(left: Char, right: Char): String =
        left + TextUtil.rule('─', PANEL_WIDTH - 2) + right

    private fun panelRow(content: String): String {
        val visibleWidth = PANEL_WIDTH - 4
        val display = TextUtil.truncateVisible(content, visibleWidth)
        return "│ ${TextUtil.padVisible(display, visibleWidth)} │"
    }
}
