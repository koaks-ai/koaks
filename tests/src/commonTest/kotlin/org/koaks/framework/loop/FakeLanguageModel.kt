package org.koaks.framework.loop

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.koaks.framework.model.LanguageModel
import org.koaks.framework.model.ModelCapabilities
import org.koaks.framework.model.ModelEvent
import org.koaks.framework.model.ModelItem
import org.koaks.framework.model.ModelRequest
import org.koaks.framework.model.ModelResponse
import org.koaks.framework.model.Usage

/**
 * A scripted [LanguageModel] for loop tests. Each call to [stream] pops the next
 * scripted list of [ModelEvent]s and replays them as a cold flow. A trailing
 * [ModelEvent.Finished] is appended if the script omitted one.
 */
class FakeLanguageModel(
    private val scripts: ArrayDeque<List<ModelEvent>>,
    override val capabilities: ModelCapabilities = ModelCapabilities(),
    private val beforeEmit: suspend (ModelEvent) -> Unit = {},
) : LanguageModel {

    constructor(vararg scripts: List<ModelEvent>) : this(ArrayDeque(scripts.toList()))

    var calls: Int = 0
        private set

    var lastRequest: ModelRequest? = null
        private set

    val requests: List<ModelRequest>
        get() = recordedRequests

    private val recordedRequests = mutableListOf<ModelRequest>()

    override fun stream(request: ModelRequest): Flow<ModelEvent> = flow {
        calls++
        lastRequest = request
        recordedRequests += request
        val events = if (scripts.isEmpty()) emptyList() else scripts.removeFirst()
        val text = StringBuilder()
        var sawFinished = false
        for (event in events) {
            if (event is ModelEvent.TextDelta) text.append(event.text)
            beforeEmit(event)
            if (event is ModelEvent.Finished) {
                sawFinished = true
                emit(enrichFinished(event, text.toString()))
            } else {
                emit(event)
            }
        }
        if (!sawFinished) {
            emit(
                ModelEvent.Finished(
                    ModelResponse.Completed(
                        output = if (text.isEmpty()) emptyList() else listOf(ModelItem.assistant(text.toString())),
                    ),
                ),
            )
        }
    }

    private fun enrichFinished(event: ModelEvent.Finished, text: String): ModelEvent.Finished {
        if (event.response.output.isNotEmpty() || text.isEmpty()) return event
        val output = listOf(ModelItem.assistant(text))
        val response = when (val r = event.response) {
            is ModelResponse.Completed -> r.copy(output = output)
            is ModelResponse.Incomplete -> r.copy(output = output)
            is ModelResponse.Failed -> r
        }
        return event.copy(response = response)
    }
}
