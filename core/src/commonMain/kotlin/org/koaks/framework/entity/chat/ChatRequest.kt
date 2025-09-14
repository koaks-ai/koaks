package org.koaks.framework.entity.chat

import org.koaks.framework.entity.Message
import org.koaks.framework.entity.ModelParams

class ChatRequest(
    val message: Message? = null,
    val messageList: List<Message>? = null,
    val modelName: String? = null,
    val params: ModelParams = ModelParams()
)