package org.koaks.cli.app

import kotlinx.coroutines.flow.collect
import org.koaks.cli.app.command.CommandRegistry
import org.koaks.cli.app.command.CommandResult
import org.koaks.cli.config.AgentConfig
import org.koaks.cli.config.CliException
import org.koaks.cli.config.Environment
import org.koaks.cli.config.PosixEnvironment
import org.koaks.cli.config.toBooleanFlagOrFalse
import org.koaks.cli.config.value
import org.koaks.cli.tui.DEFAULT_TERM_ROWS
import org.koaks.cli.tui.LineReader
import org.koaks.cli.tui.LineEditorSnapshot
import org.koaks.cli.tui.LineReadRequest
import org.koaks.cli.tui.LineSuggestion
import org.koaks.cli.tui.Output
import org.koaks.cli.tui.StdinLineReader
import org.koaks.cli.tui.StdoutOutput
import org.koaks.cli.tui.Terminal
import org.koaks.cli.tui.TerminalLayout
import org.koaks.cli.tui.Theme

internal class AgentApp(
    initialConfig: AgentConfig,
    private val output: Output = StdoutOutput(),
    private val lineReader: LineReader = StdinLineReader,
    private val environment: Environment = PosixEnvironment,
    private val commands: CommandRegistry = CommandRegistry.builtins(),
) {
    private val session = AgentSession(initialConfig)

    suspend fun run() {
        val theme = Theme(ansiEnabled(environment))
        val layout = createLayout(environment, theme)
        var closedNormally = false

        if (layout.fixedInput) InputBox.enterFixedLayout(output, layout)
        try {
            WelcomeView.render(session.config, output, theme, clearScreen = !layout.fixedInput)
            var hasCompletedTurn = false

            while (true) {
                var lastEditorSnapshot: LineEditorSnapshot? = null
                var staticMenuRows = 0
                if (layout.fixedInput) {
                    InputBox.renderFixed(output, layout, theme)
                } else {
                    InputBox.renderStaticStart(output, theme)
                }
                output.flush()

                val input = if (theme.enabled) {
                    lineReader.readLine(
                        LineReadRequest(
                            suggestions = commands.suggestions.map { suggestion ->
                                LineSuggestion(suggestion.name, suggestion.description)
                            },
                            commandNames = commands.commandNames,
                        ) { snapshot ->
                            lastEditorSnapshot = snapshot
                            if (layout.fixedInput) {
                                InputBox.renderFixedEditor(output, layout, theme, snapshot)
                            } else {
                                staticMenuRows = InputBox.renderStaticEditor(
                                    output = output,
                                    theme = theme,
                                    snapshot = snapshot,
                                    previousMenuRows = staticMenuRows,
                                )
                            }
                            output.flush()
                        }
                    )
                } else {
                    lineReader.readLine()
                }?.trimEnd() ?: break
                if (layout.fixedInput) {
                    InputBox.restoreOutputCursor(output, layout, theme)
                } else {
                    val snapshot = lastEditorSnapshot
                    if (snapshot == null) {
                        InputBox.renderStaticEnd(output, theme, Terminal.stdinIsTty())
                    } else {
                        InputBox.renderStaticInteractiveEnd(output, theme, snapshot, staticMenuRows)
                    }
                }

                val commandInput = input.trim()
                if (commandInput.isBlank()) continue

                val context = AgentContext(session, output, theme, layout)
                when (commands.dispatch(commandInput, context)) {
                    CommandResult.Exit -> break
                    CommandResult.Continue -> continue
                    null -> Unit
                }

                if (layout.fixedInput) {
                    if (hasCompletedTurn) output.writeLine()
                    output.writeLine("${theme.inputPrompt()} $input")
                }
                val events = try {
                    session.stream(input)
                } catch (e: CliException) {
                    output.writeLine(theme.error("[error] ${e.message}"))
                    continue
                }

                output.writeLine()
                output.flush()

                val eventPrinter = EventPrinter(session.config.showReasoning, output, theme)
                events.collect { event ->
                    eventPrinter.print(event)
                }
                hasCompletedTurn = true
            }
            closedNormally = true
        } finally {
            session.close()
            if (layout.fixedInput) InputBox.leaveFixedLayout(output, layout)
            if (closedNormally) {
                output.writeLine("\n${theme.dim("session closed")}")
            }
            output.flush()
        }
    }

    private fun createLayout(env: Environment, theme: Theme): TerminalLayout {
        val nativeSize = Terminal.size()
        val rows = (
            env.value("KOAKS_TERM_ROWS")?.toIntOrNull()
                ?: nativeSize?.rows
                ?: env.value("LINES")?.toIntOrNull()
                ?: DEFAULT_TERM_ROWS
            )
        val columns = (
            env.value("KOAKS_TERM_COLS")?.toIntOrNull()
                ?: nativeSize?.columns
                ?: env.value("COLUMNS")?.toIntOrNull()
                ?: PANEL_WIDTH
            )
        return TerminalLayout.of(
            rows = rows,
            columns = columns,
            fixedInput = theme.enabled && fixedInputEnabled(env),
            commandMenuRows = commands.suggestions.size,
        )
    }

    private fun ansiEnabled(env: Environment): Boolean {
        if (env.value("NO_COLOR") != null || env.value("KOAKS_NO_COLOR").toBooleanFlagOrFalse()) return false
        if (env.value("TERM") == "dumb") return false
        return true
    }

    private fun fixedInputEnabled(env: Environment): Boolean {
        when (env.value("KOAKS_FIXED_INPUT")?.lowercase()) {
            "0", "false", "no", "off" -> return false
            "1", "true", "yes", "on" -> return true
        }
        return true
    }
}
