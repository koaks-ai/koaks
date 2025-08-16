package org.koaks.framework.entity.inner

import com.google.gson.annotations.SerializedName
import org.koaks.framework.entity.Message
import org.koaks.framework.entity.ModelParams

class InnerChatRequest(
    var user: String? = null,
    @Transient
    var messageId: String? = null,
    @SerializedName("model")
    var modelName: String? = null,
    var messages: MutableList<Message>
) : ModelParams()