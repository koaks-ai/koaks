package org.endowx.framework.entity

import com.google.gson.annotations.SerializedName

class ChatRequest(
    var message: String,
    @SerializedName("model")
    var modelName: String? = null,
    var params: ModelParams = ModelParams()
)