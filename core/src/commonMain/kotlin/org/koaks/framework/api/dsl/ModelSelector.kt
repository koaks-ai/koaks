package org.koaks.framework.api.dsl

import org.koaks.framework.entity.ModelParams
import org.koaks.framework.model.AbstractChatModel

class ModelSelector {
    var selected: AbstractChatModel<*, *>? = null

    fun AbstractChatModel<*, *>.params(block: ModelParams.() -> Unit) {
        this.defaultParams.apply(block)
    }

}

