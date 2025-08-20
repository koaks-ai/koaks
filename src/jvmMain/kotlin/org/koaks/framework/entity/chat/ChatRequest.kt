package org.koaks.framework.entity.chat

import com.google.gson.annotations.SerializedName
import org.koaks.framework.entity.ModelParams

class ChatRequest(
    var message: String,
    @SerializedName("model")
    var modelName: String? = null,
    var params: ModelParams = ModelParams()
)