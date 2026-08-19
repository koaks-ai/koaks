package org.koaks.json

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okio.ByteString.Companion.encodeUtf8
import org.koaks.framework.loop.AgentEvent
import org.koaks.framework.loop.AgentState
import org.koaks.framework.loop.AgentId
import org.koaks.framework.middleware.ModelCallPhase
import org.koaks.framework.model.Annotation
import org.koaks.framework.model.AgentError
import org.koaks.framework.model.CheckpointScope
import org.koaks.framework.model.ContentPart
import org.koaks.framework.model.ItemRef
import org.koaks.framework.model.ModelEvent
import org.koaks.framework.model.ModelItem
import org.koaks.framework.model.ModelResponse
import org.koaks.framework.model.IncompleteReason
import org.koaks.framework.model.ProviderCheckpoint
import org.koaks.framework.model.ProviderId
import org.koaks.framework.model.ProviderScopedId
import org.koaks.framework.model.ReplayPolicy
import org.koaks.framework.model.ToolCall
import org.koaks.framework.model.TranscriptBasis
import org.koaks.framework.model.Usage
import org.koaks.framework.tool.ToolOutputStream
import org.koaks.framework.tool.ToolProgress
import org.koaks.framework.policy.TerminationReason
import org.koaks.runtime.acb.RunEventEnvelope
import org.koaks.runtime.acb.RunEventPayload
import org.koaks.runtime.acb.RunId
import org.koaks.runtime.acb.TurnId
import org.koaks.runtime.observe.RuntimeEvent
import org.koaks.framework.memory.ThreadId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KoaksWireJsonTest {
    @Test
    fun modelEventGoldenAndRoundTripPreserveLosslessMetadata() {
        val event = providerEventFixture()
        assertEquals(providerEventGolden, KoaksWireJson.encodeModelEvent(event))
        assertEquals(event, KoaksWireJson.decodeModelEvent(providerEventGolden))
    }

    @Test
    fun everyModelEventVariantHasStableDiscriminatorAndRoundTrips() {
        val checkpoint = ProviderCheckpoint(
            providerId = ProviderId("fixture"),
            codecVersion = 1,
            basis = TranscriptBasis(0, "digest"),
            scope = CheckpointScope.CrossTurn,
            payload = "state".encodeUtf8(),
        )
        val call = ToolCall("call", "lookup", "{}")
        val events = listOf<ModelEvent>(
            ModelEvent.Started("response"),
            ModelEvent.CheckpointUpdated(checkpoint),
            ModelEvent.TextDelta("text", ItemRef("item")),
            ModelEvent.ReasoningDelta("reason", kind = ModelEvent.ReasoningKind.SUMMARY),
            ModelEvent.RefusalDelta("refusal"),
            ModelEvent.AnnotationAdded(Annotation.Generic("future", "payload")),
            ModelEvent.ItemAdded(ModelItem.assistant("item")),
            ModelEvent.ToolCallDelta("call", 0, "lookup", "{}"),
            ModelEvent.ToolCallCompleted(call),
            providerEventFixture(),
            ModelEvent.Finished(ModelResponse.Completed(output = listOf(ModelItem.assistant("done")))),
            ModelEvent.Finished(ModelResponse.Incomplete(reason = IncompleteReason.Cancelled)),
            ModelEvent.Finished(ModelResponse.Failed(AgentError.ModelError("failed", retriable = false))),
        )
        events.forEach { event ->
            val encoded = KoaksWireJson.encodeModelEvent(event)
            assertTrue(encoded["type"] is JsonPrimitive)
            assertEquals(event, KoaksWireJson.decodeModelEvent(encoded))
        }
    }

    @Test
    fun modelItemsAndCheckpointsRoundTripOpaquePayloads() {
        val item = ModelItem.ProviderItem(
            providerId = ProviderId("fixture"),
            kind = "future_item",
            displayText = "opaque",
            replay = ReplayPolicy.Preferred,
            payload = "binary".encodeUtf8(),
        )
        assertEquals(item, KoaksWireJson.decodeModelItem(KoaksWireJson.encodeModelItem(item)))

        val message = ModelItem.Message(
            ref = ItemRef("message-1"),
            role = org.koaks.framework.model.Role.ASSISTANT,
            content = listOf(
                ContentPart.Text("hello"),
                ContentPart.Image(url = "https://example.com/image.png"),
                ContentPart.Audio(base64 = "YQ==", format = "wav"),
            ),
            annotations = listOf(Annotation.UrlCitation("https://example.com")),
        )
        assertEquals(message, KoaksWireJson.decodeModelItem(KoaksWireJson.encodeModelItem(message)))

        val checkpoint = ProviderCheckpoint(
            providerId = ProviderId("fixture"),
            codecVersion = 2,
            basis = TranscriptBasis(1, "digest"),
            scope = CheckpointScope.InRun,
            payload = "checkpoint".encodeUtf8(),
            expiresAtEpochMs = 1234,
        )
        assertEquals(checkpoint, KoaksWireJson.decodeProviderCheckpoint(KoaksWireJson.encodeProviderCheckpoint(checkpoint)))
    }

    @Test
    fun agentEventsCoverTerminalAndProgressShapes() {
        val call = ToolCall(
            id = "call-1",
            name = "lookup",
            arguments = "{}",
            nativeId = ProviderScopedId(ProviderId.OpenAI, "native-1"),
        )
        val events = listOf<AgentEvent>(
            AgentEvent.TextDelta("text", ItemRef("item-1")),
            AgentEvent.ReasoningDelta("thinking", kind = ModelEvent.ReasoningKind.SUMMARY),
            AgentEvent.Model(ModelEvent.TextDelta("model"), 1, ModelCallPhase.Normal),
            AgentEvent.ToolCallRequested(call),
            AgentEvent.ToolResult("call-1", "ok", false),
            AgentEvent.ToolProgress("call-1", ToolProgress.Output("out", ToolOutputStream.Stdout)),
            AgentEvent.ToolProgress("call-1", ToolProgress.Status("running")),
            AgentEvent.ToolProgress("call-1", ToolProgress.Custom("custom", JsonPrimitive(true))),
            AgentEvent.StepCompleted(1),
            AgentEvent.Completed(ModelItem.assistant("done"), Usage(totalTokens = 1)),
            AgentEvent.Incomplete(ModelItem.assistant("partial"), Usage.ZERO, IncompleteReason.MaxOutputTokens),
            AgentEvent.Terminated(
                ModelItem.assistant("stopped"),
                Usage.ZERO,
                TerminationReason.MaxSteps(3),
            ),
            AgentEvent.Failed(AgentError.ToolNotFound("missing")),
        )
        events.forEach { encoded ->
            val json = KoaksWireJson.encodeAgentEvent(encoded)
            assertTrue(json["type"] != null)
            assertFalse(json.containsKey("cause"), "agent event must not expose platform exceptions")
        }
    }

    @Test
    fun runEnvelopeKeepsFlatShapeAndLifecycleEvents() {
        val envelope = RunEventEnvelope(
            runId = RunId(1),
            agentId = AgentId("agent"),
            threadId = ThreadId("thread"),
            turnId = TurnId(2),
            correlationId = "corr",
            sequence = 3,
            timestampEpochMillis = 4,
            payload = RunEventPayload.Agent(AgentEvent.TextDelta("hello")),
        )
        val encoded = KoaksWireJson.encodeRunEvent(envelope)
        assertEquals("agent", encoded["kind"]?.let { (it as JsonPrimitive).content })
        assertTrue(encoded["event"] != null)
        assertTrue(encoded["payload"] == null)

        val lifecycle = RuntimeEvent.Finished(RunId(1), AgentId("agent"), ThreadId("thread"), TurnId(2), Usage.ZERO)
        val lifecycleJson = KoaksWireJson.encodeRuntimeEvent(lifecycle)
        assertEquals("finished", (lifecycleJson["type"] as JsonPrimitive).content)
        assertTrue(lifecycleJson["usage"] != null)
    }

    @Test
    fun stateAndAgentResultUseSharedNestedCodecs() {
        val state = AgentState(
            items = listOf(ModelItem.user("question")),
            instructions = "instructions",
            globalStep = 2,
            localStep = 1,
            usage = Usage(totalTokens = 4),
        )
        val stateJson = KoaksWireJson.encodeAgentState(state)
        assertEquals(1, stateJson["items"]?.let { (it as kotlinx.serialization.json.JsonArray).size })
        assertEquals("instructions", (stateJson["instructions"] as JsonPrimitive).content)

        val result = org.koaks.framework.loop.AgentResult.Completed(ModelItem.assistant("done"), Usage.ZERO)
        assertEquals("completed", (KoaksWireJson.encodeAgentResult(result)["status"] as JsonPrimitive).content)
    }

    @Test
    fun malformedWireFailsFast() {
        assertFailsWith<IllegalStateException> {
            KoaksWireJson.decodeModelEvent(buildJsonObject { put("type", JsonPrimitive("unknown")) })
        }
        assertFailsWith<IllegalStateException> {
            KoaksWireJson.decodeModelItem(buildJsonObject {
                put("type", JsonPrimitive("provider_item"))
                put("payload_base64", JsonPrimitive("!"))
            })
        }
        assertFailsWith<IllegalStateException> {
            KoaksWireJson.parseObject("[]")
        }
    }

    @Test
    fun defaultUsageMatchesNodeBridgeSemantics() {
        val decoded = KoaksWireJson.decodeUsage(buildJsonObject {})
        assertEquals(Usage.ZERO, decoded)
    }
}
