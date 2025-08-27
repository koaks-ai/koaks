package org.koaks.framework.entity.inner

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import org.koaks.framework.entity.Message
import org.koaks.framework.entity.ModelParams

@Serializable
class FullChatRequest(
    var user: String? = null,
    @Transient
    var messageId: String? = null,
    @SerialName("model")
    var modelName: String? = null,
    var messages: MutableList<Message>
) : ModelParams()