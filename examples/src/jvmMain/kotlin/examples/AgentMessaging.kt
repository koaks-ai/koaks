package examples

import org.koaks.framework.loop.Agent
import org.koaks.framework.loop.agent
import org.koaks.framework.middleware.StepDecision
import org.koaks.framework.middleware.StepPhase
import org.koaks.framework.model.ItemRef
import org.koaks.framework.model.LanguageModel
import org.koaks.framework.model.ModelItem
import org.koaks.runtime.AgentRuntime
import org.koaks.runtime.coordination.SendResult
import org.koaks.runtime.tools.childAgents

/** Supply an application-configured model; this example does not need provider credentials. */
fun messagingAgent(model: LanguageModel): Agent = agent {
    id = "coordinator"
    summary = "Coordinate code review tasks"
    model { custom(model) }
    tools { childAgents() }
    hook {
        onStep { ctx ->
            if (ctx.stage == StepPhase.BeforeModelRequest) {
                ctx.memory.append(ModelItem.user("Review the tests as well as the implementation", ItemRef("review-guidance")))
            }
            StepDecision.Continue
        }
    }
}

suspend fun sendFollowUp(runtime: AgentRuntime, worker: Agent): SendResult {
    val handle = runtime.spawn(worker, "Review authentication", summary = "Authentication review")
    val receipt = handle.send("Also inspect token refresh races")
    handle.await()
    return receipt
}
