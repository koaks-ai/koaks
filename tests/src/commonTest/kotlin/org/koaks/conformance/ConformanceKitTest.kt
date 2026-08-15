package org.koaks.conformance

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.encodeUtf8
import org.koaks.framework.loop.AgentEvent
import org.koaks.framework.loop.TurnBuilder
import org.koaks.framework.loop.agent
import org.koaks.framework.loop.done
import org.koaks.framework.loop.fail
import org.koaks.framework.memory.InterruptReason
import org.koaks.framework.memory.WindowMemory
import org.koaks.framework.memory.completedTurn
import org.koaks.framework.memory.repairTranscript
import org.koaks.framework.memory.unresolvedCallRefs
import org.koaks.framework.model.AgentError
import org.koaks.framework.model.AgentFrameworkException
import org.koaks.framework.model.CheckpointScope
import org.koaks.framework.model.ItemRef
import org.koaks.framework.model.LanguageModel
import org.koaks.framework.model.ModelCapabilities
import org.koaks.framework.model.ModelEvent
import org.koaks.framework.model.ModelItem
import org.koaks.framework.model.ModelRequest
import org.koaks.framework.model.ModelResponse
import org.koaks.framework.model.ProviderCheckpoint
import org.koaks.framework.model.ProviderId
import org.koaks.framework.model.ProviderScopedId
import org.koaks.framework.model.ReplayPolicy
import org.koaks.framework.model.Support
import org.koaks.framework.model.TranscriptBasis
import org.koaks.framework.model.takeIfValidFor
import org.koaks.framework.provider.ModelConfig
import org.koaks.framework.transport.KtorTransport
import org.koaks.provider.anthropic.AnthropicContentBlock
import org.koaks.provider.anthropic.toAnthropicMessages
import org.koaks.provider.chatcompletions.ChatCompletionsDecoder
import org.koaks.provider.chatcompletions.ChatCompletionsResponse
import org.koaks.provider.chatcompletions.toChatMessages
import org.koaks.provider.openai.OpenAIResponsesModel
import org.koaks.provider.openai.ResponsesDecoder
import org.koaks.provider.openai.ResponsesItemTypes
import org.koaks.provider.openai.ResponsesStateMode
import org.koaks.provider.openai.toInput
import org.koaks.framework.utils.json.JsonUtil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConformanceKitTest {

    @Test
    fun chat_completions_cassette_is_lossless_for_text_and_tools() = runTest {
        val decoder = ChatCompletionsDecoder(ProviderId.OpenAI)
        val events = playSse(CHAT_TOOL_CASSETTE, decoder)
        val finished = events.filterIsInstance<ModelEvent.Finished>().single()
        val completed = finished.response as ModelResponse.Completed
        assertTrue(events.any { it is ModelEvent.TextDelta })
        val call = events.filterIsInstance<ModelEvent.ToolCallCompleted>().single()
        assertEquals("get_weather", call.call.name)
        assertTrue(completed.output.any { it is ModelItem.ToolCall })
        assertEquals(call.call.nativeId?.raw, "call_1")
    }

    @Test
    fun responses_cassette_covers_text_usage_and_unknown_item() = runTest {
        val decoder = ResponsesDecoder(ResponsesStateMode.Replayable, persistCheckpoint = false, basisItems = emptyList())
        val events = playSse(RESPONSES_CASSETTE, decoder)
        val finished = events.filterIsInstance<ModelEvent.Finished>().single().response
        assertTrue(finished is ModelResponse.Completed)
        assertEquals(42, finished.usage.totalTokens)
        assertEquals(7, finished.usage.cachedInputTokens)
        assertEquals(3, finished.usage.reasoningOutputTokens)
        assertTrue(finished.output.any { it is ModelItem.Message })
        assertTrue(finished.output.any { it is ModelItem.ProviderItem && it.kind == "future_widget" })
        assertNotNull(finished.checkpoint)
        assertEquals(CheckpointScope.InRun, finished.checkpoint!!.scope)
    }

    @Test
    fun responses_unknown_item_survives_encode_round_trip() {
        val item = ModelItem.ProviderItem(
            providerId = ProviderId.OpenAIResponses,
            kind = "future_widget",
            displayText = "[future_widget]",
            replay = ReplayPolicy.Required,
            payload = """{"type":"future_widget","id":"fw_1","payload":{"x":1}}""".encodeUtf8(),
        )
        val encoded = toInput(listOf(item)).toString()
        assertTrue(encoded.contains("future_widget"))
        assertTrue(encoded.contains("fw_1"))
    }

    @Test
    fun checkpoint_round_trip_and_basis_mismatch_drops() {
        val items = listOf(ModelItem.user("hi"), ModelItem.assistant("ok"))
        val basis = TranscriptBasis.of(items)
        val checkpoint = ProviderCheckpoint(
            providerId = ProviderId.OpenAIResponses,
            codecVersion = 1,
            basis = basis,
            scope = CheckpointScope.CrossTurn,
            payload = """{"response_id":"resp_1","mode":"Replayable"}""".encodeUtf8(),
        )
        assertEquals(checkpoint, checkpoint.takeIfValidFor(items))
        assertNull(checkpoint.takeIfValidFor(items + ModelItem.user("extra")))
    }

    @Test
    fun item_ref_is_stable_across_turns_and_not_call_index() {
        val first = ItemRef.generate("call")
        val second = ItemRef.generate("call")
        assertNotEquals(first, second)
        val call = ModelItem.ToolCall(ref = first, name = "x", arguments = "{}")
        assertEquals(first, call.ref)
    }

    @Test
    fun capability_unknown_is_not_unsupported() {
        assertEquals(Support.Unknown, org.koaks.framework.model.ModelCapabilities().jsonSchema)
        assertTrue(!Support.Unknown.isKnownUnsupported)
        assertTrue(Support.Unsupported.isKnownUnsupported)
    }

    @Test
    fun interrupt_resume_repairs_orphan_tool_calls() {
        val builder = TurnBuilder("turn-1", listOf(ModelItem.user("do it")))
        val callRef = ItemRef.generate("call")
        builder.observe(
            ModelEvent.ToolCallCompleted(
                org.koaks.framework.model.ToolCall(callRef.value, "search", "{}"),
            ),
        )
        val stored = builder.interruptedTurn(InterruptReason.Cancelled)
        assertEquals(listOf(callRef), unresolvedCallRefs(stored.items))
        val online = repairTranscript(stored.items)
        val results = online.filterIsInstance<ModelItem.ToolResult>()
        assertEquals(1, results.size)
        assertTrue(results.single().isError)
        assertEquals("<interrupted: not executed>", results.single().output)
        val encoded = toInput(online).toString()
        assertTrue(encoded.contains("function_call_output"))
        assertTrue(encoded.contains("interrupted"))
    }

    @Test
    fun responses_item_type_catalog_has_twenty_two_kinds() {
        assertEquals(22, ResponsesItemTypes.ALL.size)
    }

    @Test
    fun responses_model_abandon_posts_cancel() = runTest {
        var url = ""
        val engine = MockEngine { request ->
            url = request.url.toString()
            respond("{}", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val transport = KtorTransport(HttpClient(engine) { install(HttpTimeout) })
        val model = OpenAIResponsesModel(
            config = ModelConfig(baseUrl = "https://api.openai.com/v1/responses", apiKey = "k", modelName = "gpt-4.1"),
            transport = transport,
        )
        model.abandon("resp_123")
        assertTrue(url.contains("/responses/resp_123/cancel"))
        transport.close()
    }

    @Test
    fun interrupt_repair_is_legal_for_chat_completions_and_responses() {
        val callRef = ItemRef("call_stable")
        val stored = listOf(
            ModelItem.user("do it"),
            ModelItem.ToolCall(
                ref = callRef,
                nativeId = ProviderScopedId(ProviderId.OpenAI, "call_1"),
                name = "search",
                arguments = "{}",
            ),
        )
        val online = repairTranscript(stored)
        val messages = ModelRequest(instructions = null, items = online, idempotencyKey = "k").toChatMessages()
        assertTrue(messages.any { it.role == "assistant" && it.toolCalls.orEmpty().any { call -> call.id == "call_1" } })
        assertTrue(messages.any { it.role == "tool" && it.toolCallId == "call_1" })
        val encoded = toInput(online).toString()
        assertTrue(encoded.contains("function_call_output"))
        assertTrue(encoded.contains("interrupted"))
    }

    @Test
    fun required_provider_item_is_not_silently_dropped_by_chat_completions() {
        val item = ModelItem.ProviderItem(
            providerId = ProviderId.Anthropic,
            kind = "thinking",
            displayText = "secret",
            replay = ReplayPolicy.Required,
            payload = "{}".encodeUtf8(),
        )
        val request = ModelRequest(
            instructions = null,
            items = listOf(ModelItem.user("hi"), item),
            idempotencyKey = "k",
        )
        val error = assertFailsWith<AgentFrameworkException> { request.toChatMessages() }
        assertTrue(error.error is AgentError.PreparationError)
        assertTrue(error.message!!.contains("Required"))
    }

    @Test
    fun fallback_fails_closed_on_required_native_state() = runTest {
        val required = ModelItem.ProviderItem(
            providerId = ProviderId.Anthropic,
            kind = "thinking",
            displayText = "secret",
            replay = ReplayPolicy.Required,
            payload = "{}".encodeUtf8(),
        )
        val primary = object : LanguageModel {
            override val capabilities = ModelCapabilities()
            override fun stream(request: ModelRequest): Flow<ModelEvent> = flow {
                emit(fail("primary down", retriable = true))
            }
        }
        val secondaryUsed = booleanArrayOf(false)
        val secondary = object : LanguageModel {
            override val capabilities = ModelCapabilities()
            override fun stream(request: ModelRequest): Flow<ModelEvent> = flow {
                secondaryUsed[0] = true
                emit(ModelEvent.TextDelta("should-not"))
                emit(done())
            }
        }
        val a = agent {
            id = "conformance-fallback"
            model { custom(primary).fallback(custom(secondary)) }
            hook {
                onModelCall {
                    before { ctx -> ctx.request.copy(items = ctx.request.items + required) }
                }
            }
        }
        val events = a.stream("hi").toList()
        assertTrue(!secondaryUsed[0], "required native state must not be sent to a fallback provider")
        assertTrue(events.any { it is AgentEvent.Failed })
        val failed = events.filterIsInstance<AgentEvent.Failed>().single()
        assertTrue(failed.error.message.contains("Required"))
    }

    @Test
    fun concurrent_decoders_do_not_share_tool_call_refs() {
        fun delta(id: String, name: String) = ChatCompletionsResponse(
            choices = listOf(
                ChatCompletionsResponse.Choice(
                    delta = ChatCompletionsResponse.Delta(
                        toolCalls = listOf(
                            ChatCompletionsResponse.ToolCallChunk(
                                index = 0,
                                id = id,
                                function = ChatCompletionsResponse.FunctionChunk(name = name, arguments = "{}"),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val first = ChatCompletionsDecoder(ProviderId.OpenAI)
        val second = ChatCompletionsDecoder(ProviderId.OpenAI)
        first.acceptChunk(delta("call_a", "one"))
        second.acceptChunk(delta("call_b", "two"))
        val callA = first.finish().filterIsInstance<ModelEvent.ToolCallCompleted>().single()
        val callB = second.finish().filterIsInstance<ModelEvent.ToolCallCompleted>().single()
        assertNotEquals(callA.call.id, callB.call.id)
        assertEquals("call_a", callA.call.nativeId?.raw)
        assertEquals("call_b", callB.call.nativeId?.raw)
    }

    @Test
    fun window_memory_rejects_dropping_required_items() = runTest {
        val mem = WindowMemory(maxMessages = 2)
        mem.commit(
            completedTurn(
                ModelItem.user("q1"),
                ModelItem.ProviderItem(
                    providerId = ProviderId.Anthropic,
                    kind = "thinking",
                    displayText = "secret",
                    replay = ReplayPolicy.Required,
                    payload = "{}".encodeUtf8(),
                ),
            ),
        )
        mem.commit(completedTurn(ModelItem.user("q2"), ModelItem.assistant("a2")))
        val error = assertFailsWith<AgentFrameworkException> { mem.load(emptyList()) }
        assertTrue(error.error is AgentError.PreparationError)
        assertTrue(error.message!!.contains("Required"))
    }

    @Test
    fun anthropic_thinking_signature_round_trips_verbatim() {
        val block = AnthropicContentBlock.Thinking("let me think", "sig_abc")
        val item = ModelItem.ProviderItem(
            providerId = ProviderId.Anthropic,
            kind = "thinking",
            displayText = "let me think",
            replay = ReplayPolicy.Required,
            payload = JsonUtil.toJson(block, AnthropicContentBlock.serializer()).encodeUtf8(),
        )
        val messages = toAnthropicMessages(listOf(ModelItem.user("q"), item, ModelItem.assistant("a")))
        val assistant = messages.single { it.role == "assistant" }
        val thinking = assistant.content.filterIsInstance<AnthropicContentBlock.Thinking>().single()
        assertEquals("let me think", thinking.thinking)
        assertEquals("sig_abc", thinking.signature)
    }

    @Test
    fun repaired_checkpoint_covering_synthetic_items_is_dropped() {
        val call = ModelItem.ToolCall(ref = ItemRef("call_1"), name = "x", arguments = "{}")
        val stored = listOf(ModelItem.user("q"), call)
        val online = repairTranscript(stored)
        val checkpoint = ProviderCheckpoint(
            providerId = ProviderId.OpenAIResponses,
            codecVersion = 1,
            basis = TranscriptBasis.of(stored),
            scope = CheckpointScope.CrossTurn,
            payload = """{"response_id":"resp_1","mode":"Replayable"}""".encodeUtf8(),
        )
        assertNull(checkpoint.takeIfValidFor(online))
        assertNotNull(checkpoint.takeIfValidFor(stored))
    }
}

private suspend fun playSse(cassette: String, decoder: org.koaks.framework.provider.WireDecoder): List<ModelEvent> {
    val engine = MockEngine {
        respond(cassette, headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
    }
    val transport = KtorTransport(HttpClient(engine) { install(HttpTimeout) })
    val events = mutableListOf<ModelEvent>()
    transport.call(
        org.koaks.framework.transport.WireCall(
            method = org.koaks.framework.transport.HttpMethod.POST,
            url = "http://cassette.test/v1",
            body = "{}",
            expect = org.koaks.framework.transport.Framing.Sse,
            retry = org.koaks.framework.provider.RetryBudget(maxRetries = 0),
        ),
    ).collect { frame -> events += decoder.accept(frame) }
    events += decoder.finish()
    transport.close()
    return events
}

private const val CHAT_TOOL_CASSETTE = """
data: {"id":"chatcmpl_1","choices":[{"delta":{"content":"Let me check. "}}]}

data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"get_weather","arguments":""}}]}}]}

data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"city\":\"NYC\"}"}}]}}]}

data: {"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}

data: [DONE]

"""

private const val RESPONSES_CASSETTE = """
event: response.created
data: {"type":"response.created","response":{"id":"resp_1","status":"in_progress"}}

event: response.output_text.delta
data: {"type":"response.output_text.delta","delta":"hello "}

event: response.output_text.delta
data: {"type":"response.output_text.delta","delta":"world"}

event: response.output_item.done
data: {"type":"response.output_item.done","item":{"id":"msg_1","type":"message","role":"assistant","content":[{"type":"output_text","text":"hello world"}]}}

event: response.output_item.done
data: {"type":"response.output_item.done","item":{"id":"fw_1","type":"future_widget","payload":{"x":1}}}

event: response.completed
data: {"type":"response.completed","response":{"id":"resp_1","status":"completed","usage":{"input_tokens":20,"output_tokens":22,"total_tokens":42,"input_tokens_details":{"cached_tokens":7},"output_tokens_details":{"reasoning_tokens":3}},"output":[]}}

"""
