package org.koaks.framework.loop

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import org.koaks.framework.middleware.Hook

/** Per-execution integrations; never installed on the shared Agent definition. */
internal class RunStepServices(
    val hooks: List<Hook>,
    val observeUsage: (org.koaks.framework.model.Usage) -> Unit,
    val pause: suspend (String?) -> Unit,
    val finishIfEmpty: () -> Boolean,
    val close: () -> Unit,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RunStepServices>
}
