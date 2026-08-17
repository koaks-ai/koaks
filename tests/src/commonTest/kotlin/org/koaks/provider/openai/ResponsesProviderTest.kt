package org.koaks.provider.openai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.koaks.framework.loop.TurnBuilder
import org.koaks.framework.model.Annotation
import org.koaks.framework.model.CheckpointScope
import org.koaks.framework.model.EventDetail
import org.koaks.framework.model.ItemRef
import org.koaks.framework.model.ModelEvent
import org.koaks.framework.model.ModelItem
import org.koaks.framework.model.ModelRequest
import org.koaks.framework.model.ModelResponse
import org.koaks.framework.model.ProviderId
import org.koaks.framework.model.ProtocolId
import org.koaks.framework.model.ProviderScopedId
import org.koaks.framework.model.TranscriptBasis
import org.koaks.framework.provider.ModelConfig
import org.koaks.framework.transport.ModelTransport
import org.koaks.framework.transport.WireCall
import org.koaks.framework.transport.WireFrame
import org.koaks.provider.openai.responses.OpenAIResponsesModel
import org.koaks.provider.openai.responses.ResponsesCheckpointCodec
import org.koaks.provider.openai.responses.ResponsesDecoder
import org.koaks.provider.openai.responses.ResponsesParams
import org.koaks.provider.openai.responses.ResponsesStateMode
import org.koaks.provider.openai.responses.toInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResponsesProviderTest {

    @Test
    fun streaming_function_call_is_dispatched_once() {
        val decoder =
            ResponsesDecoder(ResponsesStateMode.Replayable, persistCheckpoint = false, basisItems = emptyList())
        val events = play(
            decoder,
            "response.created" to """{"type":"response.created","response":{"id":"resp_1"}}""",
            "response.output_item.added" to """{"type":"response.output_item.added","output_index":0,"item":{"id":"fc_1","type":"function_call","name":"get_local_city","call_id":"call_1","arguments":""}}""",
            "response.function_call_arguments.delta" to """{"type":"response.function_call_arguments.delta","output_index":0,"item_id":"fc_1","delta":"{}"}""",
            "response.output_item.done" to """{"type":"response.output_item.done","output_index":0,"item":{"id":"fc_1","type":"function_call","name":"get_local_city","call_id":"call_1","arguments":"{}"}}""",
            "response.completed" to """{"type":"response.completed","response":{"id":"resp_1","status":"completed","usage":{"input_tokens":1,"output_tokens":1,"total_tokens":2},"output":[{"id":"fc_1","type":"function_call","name":"get_local_city","call_id":"call_1","arguments":"{}"}]}}""",
        )
        val completed = events.filterIsInstance<ModelEvent.ToolCallCompleted>()
        assertEquals(1, completed.size)
        assertEquals("get_local_city", completed.single().call.name)
        assertEquals("call_1", completed.single().call.nativeId?.raw)
        assertEquals("fc_1", completed.single().call.nativeItemId?.raw)
        val finished = events.filterIsInstance<ModelEvent.Finished>().single()
        assertEquals(1, finished.response.output.filterIsInstance<ModelItem.ToolCall>().size)
        val stored = finished.response.output.filterIsInstance<ModelItem.ToolCall>().single()
        assertEquals("call_1", stored.nativeId?.raw)
        assertEquals("fc_1", stored.nativeItemId?.raw)
    }

    @Test
    fun function_call_output_uses_call_id_without_item_reference() {
        val callRef = ItemRef("c1")
        val encoded = toInput(
            listOf(
                ModelItem.user("q"),
                ModelItem.ToolCall(
                    ref = callRef,
                    nativeId = ProviderScopedId(ProviderId.OpenAIResponses, "call_1"),
                    name = "get_local_city",
                    arguments = "{}",
                    nativeItemId = ProviderScopedId(ProviderId.OpenAIResponses, "fc_1"),
                ),
                ModelItem.ToolResult(callRef = callRef, output = "西安"),
            ),
        ).toString()
        assertTrue(encoded.contains("\"id\":\"fc_1\""), encoded)
        assertTrue(encoded.contains("\"call_id\":\"call_1\""), encoded)
        assertFalse(encoded.contains("item_reference"), encoded)
    }

    @Test
    fun replayable_does_not_send_previous_response_id() = runTest {
        val body = captureBody(
            mode = ResponsesStateMode.Replayable,
            checkpointResponseId = "resp_1",
        )
        assertFalse(body.contains("previous_response_id"), body)
        assertTrue(body.contains("get_local_city"), body)
        assertTrue(body.contains("西安"), body)
        assertTrue(body.contains("\"store\":false"), body)
        assertFalse(body.contains("item_reference"), body)
        assertTrue(body.contains("\"id\":\"fc_1\""), body)
    }

    @Test
    fun server_stored_sends_previous_response_id_and_suffix_only() = runTest {
        val body = captureBody(
            mode = ResponsesStateMode.ServerStored,
            checkpointResponseId = "resp_1",
        )
        assertTrue(body.contains("\"previous_response_id\":\"resp_1\""), body)
        assertTrue(body.contains("西安"), body)
        assertFalse(body.contains("hello"), body)
        assertTrue(body.contains("\"store\":true"), body)
    }

    @Test
    fun function_call_ignores_foreign_native_ids_and_does_not_invent_item_id() {
        val encoded = toInput(
            listOf(
                ModelItem.ToolCall(
                    ref = ItemRef("core_call"),
                    nativeId = ProviderScopedId(ProviderId.Anthropic, "toolu_foreign"),
                    nativeItemId = ProviderScopedId(ProviderId.Anthropic, "item_foreign"),
                    name = "tool",
                    arguments = "{}",
                ),
            ),
        ).toString()

        assertTrue(encoded.contains("\"call_id\":\"core_call\""), encoded)
        assertFalse(encoded.contains("toolu_foreign"), encoded)
        assertFalse(encoded.contains("item_foreign"), encoded)
        assertFalse(encoded.contains("\"id\""), encoded)
    }

    @Test
    fun streaming_message_has_one_stable_item_and_preserves_refusal_and_annotations() {
        val decoder =
            ResponsesDecoder(ResponsesStateMode.Replayable, persistCheckpoint = false, basisItems = emptyList())
        val events = play(
            decoder,
            "response.created" to """{"type":"response.created","response":{"id":"resp_1"}}""",
            "response.output_item.added" to """{"type":"response.output_item.added","output_index":0,"item":{"id":"msg_1","type":"message","role":"assistant","content":[]}}""",
            "response.output_text.delta" to """{"type":"response.output_text.delta","output_index":0,"item_id":"msg_1","delta":"hello"}""",
            "response.refusal.delta" to """{"type":"response.refusal.delta","output_index":0,"item_id":"msg_1","delta":"cannot"}""",
            "response.output_text.annotation.added" to """{"type":"response.output_text.annotation.added","output_index":0,"item_id":"msg_1","annotation":{"type":"url_citation","url":"https://example.com","title":"Example"}}""",
            "response.output_item.done" to """{"type":"response.output_item.done","output_index":0,"item":{"id":"msg_1","type":"message","role":"assistant","content":[{"type":"output_text","text":"hello","annotations":[{"type":"url_citation","url":"https://example.com","title":"Example"}]},{"type":"refusal","refusal":"cannot"}]}}""",
            "response.completed" to """{"type":"response.completed","response":{"id":"resp_1","status":"completed","output":[{"id":"msg_1","type":"message","role":"assistant","content":[{"type":"output_text","text":"hello","annotations":[{"type":"url_citation","url":"https://example.com","title":"Example"}]},{"type":"refusal","refusal":"cannot"}]}]}}""",
        )
        val delta = events.filterIsInstance<ModelEvent.TextDelta>().single()
        val finished = events.filterIsInstance<ModelEvent.Finished>().single().response
        val message = finished.output.filterIsInstance<ModelItem.Message>().single()
        assertEquals(delta.itemRef, message.ref)
        assertEquals("hello", message.text)
        assertEquals("cannot", message.refusal)
        assertTrue(message.annotations.single() is Annotation.UrlCitation)
        assertEquals("cannot", events.filterIsInstance<ModelEvent.RefusalDelta>().single().text)
        assertTrue(events.filterIsInstance<ModelEvent.AnnotationAdded>().single().annotation is Annotation.UrlCitation)
        assertTrue(events.any { it is ModelEvent.CheckpointUpdated })

        val builder = TurnBuilder("turn", listOf(ModelItem.user("q")))
        events.forEach(builder::observe)
        assertEquals(1, builder.snapshot().filterIsInstance<ModelItem.Message>().count { it.ref == message.ref })
    }

    @Test
    fun lossless_preserves_every_frame_before_derived_events() {
        val decoder = ResponsesDecoder(
            ResponsesStateMode.Replayable,
            persistCheckpoint = false,
            basisItems = emptyList(),
            eventDetail = EventDetail.LOSSLESS,
        )
        val frames = listOf(
            WireFrame.Sse(
                "response.output_text.delta",
                """{"type":"response.output_text.delta","output_index":0,"delta":"hello","sequence_number":7}""",
                "evt-1",
            ),
            WireFrame.Sse("response.future.delta", """{"type":"response.future.delta","future":true}""", null),
            WireFrame.Sse(
                "response.web_search_call.searching",
                """{"type":"response.web_search_call.searching","output_index":1,"item_id":"ws_1"}""",
                null,
            ),
            WireFrame.Ndjson("{broken"),
            WireFrame.Body("application/json", """{"id":"resp_1","status":"completed","output":[]}"""),
        )

        val events = frames.flatMap(decoder::accept)
        val raw = events.filterIsInstance<ModelEvent.ProviderEvent>()

        assertEquals(frames.size, raw.size)
        assertEquals(
            frames.map {
                when (it) {
                    is WireFrame.Sse -> it.data
                    is WireFrame.Ndjson -> it.line
                    is WireFrame.Body -> it.text
                    is WireFrame.HttpError -> it.body
                }
            },
            raw.map { it.payload },
        )
        assertTrue(events.indexOf(raw.first()) < events.indexOfFirst { it is ModelEvent.TextDelta })
        assertEquals(ProtocolId.OpenAIResponses, raw.first().protocolId)
        assertEquals("evt-1", raw.first().eventId)
        assertEquals(7L, raw.first().sequenceNumber)
        assertEquals(ModelEvent.ProviderEventSource.BODY, raw.last().source)
        assertEquals("application/json", raw.last().contentType)
    }

    @Test
    fun distinguishes_summary_and_raw_reasoning_with_one_stable_item_ref() {
        val decoder = ResponsesDecoder(
            ResponsesStateMode.Replayable,
            persistCheckpoint = false,
            basisItems = emptyList(),
        )
        val events = play(
            decoder,
            "response.output_item.added" to """{"type":"response.output_item.added","output_index":0,"item":{"id":"rs_1","type":"reasoning","summary":[]}}""",
            "response.reasoning_summary_text.delta" to """{"type":"response.reasoning_summary_text.delta","output_index":0,"item_id":"rs_1","delta":"summary"}""",
            "response.reasoning_text.delta" to """{"type":"response.reasoning_text.delta","output_index":0,"item_id":"rs_1","delta":"raw"}""",
            "response.output_item.done" to """{"type":"response.output_item.done","output_index":0,"item":{"id":"rs_1","type":"reasoning","summary":[{"type":"summary_text","text":"summary"}]}}""",
        )

        val reasoning = events.filterIsInstance<ModelEvent.ReasoningDelta>()
        assertEquals(listOf(ModelEvent.ReasoningKind.SUMMARY, ModelEvent.ReasoningKind.RAW), reasoning.map { it.kind })
        assertEquals(1, reasoning.map { it.itemRef }.toSet().size)
        val item = events.filterIsInstance<ModelEvent.Finished>().single().response.output
            .filterIsInstance<ModelItem.ReasoningSummary>().single()
        assertEquals(reasoning.first().itemRef, item.ref)
    }

    @Test
    fun background_mode_polls_until_a_terminal_response() = runTest {
        val transport = SequencedTransport(
            WireFrame.Body("application/json", """{"id":"resp_1","status":"queued","output":[]}"""),
            WireFrame.Body("application/json", """{"id":"resp_1","status":"in_progress","output":[]}"""),
            WireFrame.Body("application/json", """{"id":"resp_1","status":"completed","output":[{"id":"msg_1","type":"message","role":"assistant","content":[{"type":"output_text","text":"done","annotations":[]}]}]}"""),
        )
        val model = OpenAIResponsesModel(
            config = ModelConfig(
                baseUrl = "https://api.openai.com/v1/responses",
                apiKey = "k",
                modelName = "gpt-4.1",
                requestTimeoutMs = 10_000,
            ),
            transport = transport,
            params = ResponsesParams(background = true, backgroundPollIntervalMs = 1),
        )

        val events = model.stream(
            ModelRequest(instructions = null, items = listOf(ModelItem.user("q")), idempotencyKey = "key"),
        ).toList()

        assertEquals(listOf(org.koaks.framework.transport.HttpMethod.POST, org.koaks.framework.transport.HttpMethod.GET, org.koaks.framework.transport.HttpMethod.GET), transport.calls.map { it.method })
        assertEquals("key", transport.calls.first().idempotencyKey)
        assertTrue(transport.calls.drop(1).all { it.idempotencyKey == null && it.url.endsWith("/resp_1") })
        val response = events.filterIsInstance<ModelEvent.Finished>().single().response
        assertTrue(response is ModelResponse.Completed)
        assertEquals("done", response.output.filterIsInstance<ModelItem.Message>().single().text)
    }

    @Test
    fun incomplete_body_preserves_nested_reason() {
        val decoder =
            ResponsesDecoder(ResponsesStateMode.Replayable, persistCheckpoint = false, basisItems = emptyList())
        val events = decoder.accept(
            WireFrame.Body(
                "application/json",
                """{"id":"resp_1","status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},"output":[]}""",
            ),
        ) + decoder.finish()

        val response = events.filterIsInstance<ModelEvent.Finished>().single().response
        assertTrue(response is ModelResponse.Incomplete)
        assertEquals(org.koaks.framework.model.IncompleteReason.MaxOutputTokens, response.reason)
    }

    @Test
    fun cancelled_background_body_maps_to_incomplete_cancelled() {
        val decoder =
            ResponsesDecoder(ResponsesStateMode.ServerStored, persistCheckpoint = true, basisItems = emptyList())
        val events = decoder.accept(
            WireFrame.Body(
                "application/json",
                """{"id":"resp_1","status":"cancelled","output":[]}""",
            ),
        ) + decoder.finish()

        val response = events.filterIsInstance<ModelEvent.Finished>().single().response
        assertTrue(response is ModelResponse.Incomplete)
        assertEquals(org.koaks.framework.model.IncompleteReason.Cancelled, response.reason)
    }

    @Test
    fun background_polling_respects_the_request_timeout() = runTest {
        val transport = AlwaysQueuedTransport()
        val model = OpenAIResponsesModel(
            config = ModelConfig(
                baseUrl = "https://api.openai.com/v1/responses",
                apiKey = "k",
                modelName = "gpt-4.1",
                requestTimeoutMs = 5,
            ),
            transport = transport,
            params = ResponsesParams(background = true, backgroundPollIntervalMs = 1),
        )

        val events = model.stream(
            ModelRequest(instructions = null, items = listOf(ModelItem.user("q")), idempotencyKey = "key"),
        ).toList()

        val response = events.filterIsInstance<ModelEvent.Finished>().single().response
        assertTrue(response is ModelResponse.Failed)
        assertTrue(response.error.message.contains("did not finish"))
        assertTrue(transport.calls > 1)
    }

    private suspend fun captureBody(mode: ResponsesStateMode, checkpointResponseId: String): String {
        val user = ModelItem.user("hello")
        val callRef = ItemRef("c1")
        val toolCall = ModelItem.ToolCall(
            ref = callRef,
            nativeId = ProviderScopedId(ProviderId.OpenAIResponses, "call_1"),
            name = "get_local_city",
            arguments = "{}",
            nativeItemId = ProviderScopedId(ProviderId.OpenAIResponses, "fc_1"),
        )
        val result = ModelItem.ToolResult(callRef = callRef, output = "西安")
        val prefix = listOf(user, toolCall)
        val transport = CapturingTransport()
        val model = OpenAIResponsesModel(
            config = ModelConfig(baseUrl = "https://api.openai.com/v1/responses", apiKey = "k", modelName = "gpt-4.1"),
            transport = transport,
            params = ResponsesParams(stateMode = mode),
        )
        val checkpoint = ResponsesCheckpointCodec().encode(
            responseId = checkpointResponseId,
            mode = mode,
            basis = TranscriptBasis.of(prefix),
            scope = CheckpointScope.InRun,
        )
        model.stream(
            ModelRequest(
                instructions = null,
                items = prefix + result,
                checkpoint = checkpoint,
                idempotencyKey = "k1",
            ),
        ).toList()
        return transport.last!!.body!!
    }

    private fun play(decoder: ResponsesDecoder, vararg frames: Pair<String, String>): List<ModelEvent> {
        val events = mutableListOf<ModelEvent>()
        frames.forEach { (event, data) ->
            events += decoder.accept(WireFrame.Sse(event, data, null))
        }
        events += decoder.finish()
        return events
    }
}

private class CapturingTransport : ModelTransport {
    var last: WireCall? = null
    override fun call(call: WireCall): Flow<WireFrame> = flow {
        last = call
        emit(WireFrame.HttpError(400, "application/json", """{"error":{"message":"stop"}}"""))
    }
    override fun close() = Unit
}

private class SequencedTransport(vararg frames: WireFrame) : ModelTransport {
    private val frames = ArrayDeque(frames.toList())
    val calls = mutableListOf<WireCall>()

    override fun call(call: WireCall): Flow<WireFrame> = flow {
        calls += call
        emit(frames.removeFirst())
    }

    override fun close() = Unit
}

private class AlwaysQueuedTransport : ModelTransport {
    var calls: Int = 0
    override fun call(call: WireCall): Flow<WireFrame> = flow {
        calls++
        emit(WireFrame.Body("application/json", """{"id":"resp_1","status":"queued","output":[]}"""))
    }
    override fun close() = Unit
}
