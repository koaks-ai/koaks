package org.koaks.cli.app

import org.koaks.cli.tui.Output
import org.koaks.cli.tui.TerminalLayout
import org.koaks.cli.tui.Theme

internal data class AgentContext(
    val session: AgentSession,
    val output: Output,
    val theme: Theme,
    val layout: TerminalLayout,
) {
    val config get() = session.config
}
