package org.koaks.java

import org.koaks.framework.loop.ModelScope
import org.koaks.framework.loop.ModelSelection
import org.koaks.framework.model.LanguageModel

/** Java-facing, immutable description of a model selection and its fallbacks. */
class ModelSpec private constructor(
    private val selector: (ModelScope) -> ModelSelection,
) {
    /** Appends [next] as a lower-priority fallback. */
    fun fallback(next: ModelSpec): ModelSpec {
        require(next !== this) { "a model cannot fall back to itself" }
        return ModelSpec { scope -> select(scope).fallback(next.select(scope)) }
    }

    @JvmSynthetic
    fun select(scope: ModelScope): ModelSelection = selector(scope)

    companion object {
        @JvmSynthetic
        fun create(selector: (ModelScope) -> ModelSelection): ModelSpec = ModelSpec(selector)
    }
}

/** Advanced model factories for Java callers that already have a core model. */
object Models {
    @JvmStatic
    fun custom(model: LanguageModel): ModelSpec = ModelSpec.create { scope -> scope.custom(model) }
}
