package org.koaks.runtime

import kotlinx.coroutines.flow.MutableStateFlow

private sealed interface DefaultRuntimeState {
    data class Configured(val config: AgentRuntimeConfig) : DefaultRuntimeState
    data class Running(val runtime: AgentRuntime) : DefaultRuntimeState
    data object Closed : DefaultRuntimeState
}

internal object DefaultRuntimeHolder {
    private val state = MutableStateFlow<DefaultRuntimeState>(DefaultRuntimeState.Configured(AgentRuntimeConfig()))
    private val hookInstalled = MutableStateFlow(false)

    fun configure(configure: AgentRuntimeConfig.() -> Unit) {
        while (true) {
            val current = state.value
            check(current is DefaultRuntimeState.Configured) { "default AgentRuntime is already initialized or closed" }
            val next = DefaultRuntimeState.Configured(current.config.snapshot().apply(configure))
            if (state.compareAndSet(current, next)) return
        }
    }

    fun get(): AgentRuntime {
        while (true) {
            when (val current = state.value) {
                is DefaultRuntimeState.Running -> return current.runtime
                DefaultRuntimeState.Closed -> error("default AgentRuntime is closed")
                is DefaultRuntimeState.Configured -> {
                    val runtime = AgentRuntime(current.config.snapshot())
                    if (state.compareAndSet(current, DefaultRuntimeState.Running(runtime))) {
                        installHookOnce()
                        return runtime
                    }
                    runtime.close()
                }
            }
        }
    }

    fun shutdown() {
        while (true) {
            when (val current = state.value) {
                DefaultRuntimeState.Closed -> return
                is DefaultRuntimeState.Configured -> if (state.compareAndSet(current, DefaultRuntimeState.Closed)) return
                is DefaultRuntimeState.Running -> if (state.compareAndSet(current, DefaultRuntimeState.Closed)) {
                    current.runtime.close()
                    return
                }
            }
        }
    }

    fun resetForTesting(configure: AgentRuntimeConfig.() -> Unit) {
        (state.value as? DefaultRuntimeState.Running)?.runtime?.close()
        state.value = DefaultRuntimeState.Configured(AgentRuntimeConfig().apply(configure))
    }

    fun overrideForTesting(runtime: AgentRuntime) {
        val current = state.value
        if (current is DefaultRuntimeState.Running && current.runtime !== runtime) current.runtime.close()
        state.value = DefaultRuntimeState.Running(runtime)
        installHookOnce()
    }

    private fun installHookOnce() {
        if (hookInstalled.compareAndSet(expect = false, update = true)) {
            installDefaultRuntimeShutdownHook(::shutdown)
        }
    }
}
