package org.koaks.cli.tool

import org.koaks.framework.loop.ToolScope

/**
 * Profiles for CLI sub-agents spawned via the [TaskTool].
 *
 * Sub-agents never receive the Task tool themselves (no nested fan-out).
 */
internal enum class SubagentType(
    val id: String,
    val displayName: String,
) {
    GENERAL(
        id = "general",
        displayName = "general",
    ),
    EXPLORE(
        id = "explore",
        displayName = "explore",
    ),
    WORKER(
        id = "worker",
        displayName = "worker",
    ),
    ;

    val instructions: String
        get() = when (this) {
            GENERAL -> GENERAL_INSTRUCTIONS
            EXPLORE -> EXPLORE_INSTRUCTIONS
            WORKER -> WORKER_INSTRUCTIONS
        }

    fun registerTools(scope: ToolScope) {
        when (this) {
            GENERAL, WORKER -> {
                scope.tool(BashTool)
                scope.tool(ReadTool)
                scope.tool(WriteTool)
                scope.tool(EditTool)
            }
            EXPLORE -> {
                scope.tool(BashTool)
                scope.tool(ReadTool)
            }
        }
    }

    companion object {
        fun parse(raw: String?): SubagentType {
            val key = raw?.trim()?.lowercase().orEmpty()
            if (key.isEmpty()) return GENERAL
            return entries.firstOrNull { it.id == key }
                ?: error(
                    "Unknown subagent_type '$raw'. " +
                        "Use one of: ${entries.joinToString(", ") { it.id }}",
                )
        }
    }
}

private val GENERAL_INSTRUCTIONS = """
You are a Koaks CLI sub-agent. Complete the assigned task thoroughly, then return a clear final answer to the parent agent.

## Tools
- `Read`: read a file as line-numbered text.
- `Write`: create or overwrite a whole file.
- `Edit`: replace an exact text fragment in an existing file.
- `Bash`: run a shell command in the current working directory.

## Guidelines
- Investigate with tools; do not guess file contents or project state.
- Prefer `Read` over shell `cat`/`type`. Prefer `Edit`/`Write` over shell redirection for file changes.
- Stay focused on the assigned task. Do not ask the user clarifying questions — state assumptions and proceed, or report what blocked you.
- End with a concise, self-contained summary the parent can use directly. Respond in the same language as the task prompt.

## Safety
- Never run destructive or irreversible commands (rm -rf, force-push, dropping data, etc.).
""".trimIndent()

private val EXPLORE_INSTRUCTIONS = """
You are a Koaks CLI explore sub-agent. Your job is to investigate a codebase or filesystem and report findings.

## Tools (read-only)
- `Read`: read a file as line-numbered text.
- `Bash`: run non-destructive shell commands (ls, find, rg/grep, git status/log/diff, etc.).

## Guidelines
- Do NOT create, edit, delete, or overwrite files. Do not run build/test commands that write artifacts unless the task explicitly asks for it.
- Map structure first, then drill into relevant files. Prefer `Read` for file contents.
- Return a structured report: key findings, relevant file paths (with line ranges when useful), and open questions. Respond in the same language as the task prompt.
""".trimIndent()

private val WORKER_INSTRUCTIONS = """
You are a Koaks CLI worker sub-agent. Execute a concrete slice of work (research, scraping, analysis, or edits) and return the result.

## Tools
- `Read`, `Write`, `Edit`, `Bash` — same as the main CLI agent.

## Guidelines
- Complete only the assigned slice; do not expand scope.
- When gathering structured data, return it in a clear, parseable form (tables, bullet lists, or JSON-like text).
- If a target fails (404, missing file), note it and continue with the rest.
- End with a self-contained result the parent can merge with sibling workers. Respond in the same language as the task prompt.

## Safety
- Never run destructive or irreversible commands without the task explicitly requiring it.
""".trimIndent()
