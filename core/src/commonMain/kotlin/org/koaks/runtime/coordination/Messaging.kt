package org.koaks.runtime.coordination

import kotlinx.coroutines.flow.Flow
import org.koaks.framework.model.ModelItem
import org.koaks.runtime.run.RunId

private suspend fun agentContext(): AgentContext =
    currentAgentContext() ?: error("Messaging is only available inside a runtime-managed agent")

/** Convenience functions delegate to the current run's context. */
suspend fun sendMessage(to: RunId, type: String, payload: String = "", context: List<ModelItem> = emptyList(), priority: Int = 0, deadlineMillis: Long? = null) =
    agentContext().send(to, type, payload, context, priority, deadlineMillis)
suspend fun receiveMessage(): MessageEnvelope = agentContext().receive()
suspend fun requestMessage(to: RunId, type: String, payload: String = "", context: List<ModelItem> = emptyList()): MessageEnvelope =
    agentContext().request(to, type, payload, context)
suspend fun replyMessage(to: MessageEnvelope, payload: String, context: List<ModelItem> = emptyList()) = agentContext().reply(to, payload, context)
suspend fun publishMessage(topic: String, type: String, payload: String = "", context: List<ModelItem> = emptyList(), priority: Int = 0, deadlineMillis: Long? = null) = agentContext().publish(topic, type, payload, context, priority, deadlineMillis)
suspend fun subscribeTopic(topic: String): Flow<MessageEnvelope> = agentContext().subscribe(topic)
