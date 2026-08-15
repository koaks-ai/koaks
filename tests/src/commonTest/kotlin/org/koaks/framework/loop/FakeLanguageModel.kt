package org.koaks.framework.loop

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.koaks.framework.model.LanguageModel
import org.koaks.framework.model.ModelCapabilities
import org.koaks.framework.model.ModelEvent
import org.koaks.framework.model.ModelItem
import org.koaks.framework.model.ModelRequest
import org.koaks.framework.model.ModelResponse
import org.koaks.framework.model.ItemRef
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
        var textRef: ItemRef? = null
        val output = mutableListOf<ModelItem>()

        fun upsert(item: ModelItem) {
            val index = output.indexOfFirst { it.ref == item.ref }
            if (index >= 0) output[index] = item else output += item
        }

        var sawFinished = false
        for (event in events) {
            var outgoing = event
            when (event) {
                is ModelEvent.TextDelta -> {
                    if (textRef == null) textRef = event.itemRef ?: ItemRef.generate("msg")
                    text.append(event.text)
                    upsert(ModelItem.assistant(text.toString(), ref = textRef!!))
                    outgoing = event.copy(itemRef = textRef)
                }
                is ModelEvent.ItemAdded -> upsert(event.item)
                is ModelEvent.ToolCallCompleted -> upsert(event.call.toItem())
                else -> Unit
            }
            beforeEmit(outgoing)
            if (event is ModelEvent.Finished) {
                sawFinished = true
                emit(enrichFinished(event, text.toString(), textRef, output))
            } else {
                emit(outgoing)
            }
        }
        if (!sawFinished) {
            emit(
                ModelEvent.Finished(
                    ModelResponse.Completed(
                        output = buildList {
                            addAll(output)
                        },
                    ),
                ),
            )
        }
    }

    private fun enrichFinished(
        event: ModelEvent.Finished,
        text: String,
        textRef: ItemRef?,
        observed: List<ModelItem>,
    ): ModelEvent.Finished {
        val output = observed.toMutableList()
        if (text.isNotEmpty()) {
            val message = ModelItem.assistant(text, ref = textRef ?: ItemRef.generate("msg"))
            val index = output.indexOfFirst { it.ref == message.ref }
            if (index >= 0) output[index] = message else output += message
        }
        event.response.output.forEach { item ->
            val index = output.indexOfFirst { it.ref == item.ref }
            if (index >= 0) output[index] = item else output += item
        }
        val response = when (val r = event.response) {
            is ModelResponse.Completed -> r.copy(output = output.toList())
            is ModelResponse.Incomplete -> r.copy(output = output.toList())
            is ModelResponse.Failed -> r
        }
        return event.copy(response = response)
    }
}
