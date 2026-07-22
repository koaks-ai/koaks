package org.koaks.cli.app

import kotlinx.coroutines.flow.Flow
import org.koaks.cli.agent.AgentFactory
import org.koaks.cli.config.AgentConfig
import org.koaks.framework.loop.Agent
import org.koaks.framework.loop.AgentEvent
import org.koaks.framework.memory.ThreadId
import org.koaks.runtime.AgentRuntime

internal class CliChatSession(
    initialConfig: AgentConfig,
    private val runtime: AgentRuntime,
    private val trace: CliTrace? = null,
) : AutoCloseable {
    var config: AgentConfig = initialConfig
        private set

    private var assistant: Agent? = null

    fun updateConfig(transform: (AgentConfig) -> AgentConfig) {
        config = transform(config)
        resetAgent()
    }

    fun stream(input: String): Flow<AgentEvent> {
        val activeAgent = assistant ?: AgentFactory.build(config, trace).also { replacement ->
            runtime.replaceAgent(replacement)
            assistant = replacement
        }
        return runtime.stream(activeAgent, input, thread = ThreadId(config.threadId))
    }

    private fun resetAgent() {
        assistant?.close()
        assistant = null
    }

    override fun close() {
        resetAgent()
    }
}
