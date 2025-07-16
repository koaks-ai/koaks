package org.endow.framework.entity

import com.google.gson.annotations.SerializedName

class DefaultRequest(
    var user: String? = null,
    @SerializedName("model")
    var modelName: String? = null,
    var messages: MutableList<Message>
) : ModelParams()