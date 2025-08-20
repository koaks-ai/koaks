package org.koaks.framework.entity.chat

import kotlinx.serialization.SerialName
import org.koaks.framework.entity.ModelParams

class ChatRequest(
    var message: String,
    @SerialName("model")
    var modelName: String? = null,
    var params: ModelParams = ModelParams()
)