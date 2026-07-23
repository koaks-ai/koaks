package org.koaks.framework.loop

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koaks.framework.model.AgentError
import org.koaks.framework.model.AgentFrameworkException
import org.koaks.framework.skill.PreparedSkills
import org.koaks.framework.skill.SkillDescriptor
import org.koaks.framework.skill.SkillPlan
import org.koaks.framework.skill.SkillResourceTool
import org.koaks.framework.tool.ToolRegistry

/** Owns the concurrent, once-only initialization of one immutable Agent definition. */
internal class AgentPreparation(
    private val baseInstructions: Instructions,
    private val tools: ToolRegistry,
    private val skillPlan: SkillPlan?,
) {
    private val mutex = Mutex()
    private val state = MutableStateFlow<State>(State.Pending)

    val skillDescriptors: List<SkillDescriptor>
        get() = (state.value as? State.Ready)?.definition?.skillDescriptors.orEmpty()

    suspend fun await(): PreparedAgentDefinition {
        when (val current = state.value) {
            is State.Ready -> return current.definition
            is State.Failed -> throw current.failure
            State.Pending -> Unit
        }

        return mutex.withLock {
            when (val current = state.value) {
                is State.Ready -> current.definition
                is State.Failed -> throw current.failure
                State.Pending -> prepareDefinition()
            }
        }
    }

    private suspend fun prepareDefinition(): PreparedAgentDefinition {
        return try {
            val preparedSkills = skillPlan?.prepare() ?: PreparedSkills(emptyList())
            tools.prepare(preparedSkills.additionalTools())
            val definition = PreparedAgentDefinition(
                instructions = baseInstructions.appendStatic(preparedSkills.instructionSegments),
                skillDescriptors = preparedSkills.descriptors,
            )
            state.value = State.Ready(definition)
            definition
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: AgentFrameworkException) {
            state.value = State.Failed(failure)
            throw failure
        } catch (failure: Throwable) {
            val frameworkFailure = AgentFrameworkException(
                AgentError.PreparationError(
                    component = "agent",
                    message = "agent preparation failed: ${failure.message ?: "unknown error"}",
                    cause = failure,
                ),
            )
            state.value = State.Failed(frameworkFailure)
            throw frameworkFailure
        }
    }

    private fun PreparedSkills.additionalTools() = buildList {
        addAll(tools)
        if (resourceProviders.isNotEmpty()) add(SkillResourceTool(resourceProviders))
    }

    private sealed interface State {
        data object Pending : State
        data class Ready(val definition: PreparedAgentDefinition) : State
        data class Failed(val failure: AgentFrameworkException) : State
    }
}

internal data class PreparedAgentDefinition(
    val instructions: Instructions,
    val skillDescriptors: List<SkillDescriptor>,
)
