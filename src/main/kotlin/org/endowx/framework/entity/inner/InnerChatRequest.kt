package org.endowx.framework.entity.inner

import com.google.gson.annotations.SerializedName
import org.endowx.framework.entity.Message
import org.endowx.framework.entity.ModelParams

class InnerChatRequest(
    var user: String? = null,
    @Transient
    var messageId: String? = null,
    @SerializedName("model")
    var modelName: String? = null,
    var messages: MutableList<Message>
) : ModelParams()