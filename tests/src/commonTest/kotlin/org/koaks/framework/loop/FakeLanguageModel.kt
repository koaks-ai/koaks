package org.koaks.framework.loop

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.koaks.framework.model.ChatRequest
import org.koaks.framework.model.LanguageModel
import org.koaks.framework.model.ModelCapabilities
import org.koaks.framework.model.ModelEvent

/**
 * A scripted [LanguageModel] for loop tests. Each call to [generate] pops the next
 * scripted list of [ModelEvent]s and replays them as a cold flow. Optionally runs a
 * [beforeEmit] hook between events (used to assert ordering / inject delays).
 */
class FakeLanguageModel(
    private val scripts: ArrayDeque<List<ModelEvent>>,
    override val capabilities: ModelCapabilities = ModelCapabilities(),
    private val beforeEmit: suspend (ModelEvent) -> Unit = {},
) : LanguageModel {

    constructor(vararg scripts: List<ModelEvent>) : this(ArrayDeque(scripts.toList()))

    var calls: Int = 0
        private set

    var lastRequest: ChatRequest? = null
        private set

    val requests: List<ChatRequest>
        get() = recordedRequests

    private val recordedRequests = mutableListOf<ChatRequest>()

    override fun generate(request: ChatRequest): Flow<ModelEvent> = flow {
        calls++
        lastRequest = request
        recordedRequests += request
        val events = if (scripts.isEmpty()) emptyList() else scripts.removeFirst()
        for (e in events) {
            beforeEmit(e)
            emit(e)
        }
    }
}
