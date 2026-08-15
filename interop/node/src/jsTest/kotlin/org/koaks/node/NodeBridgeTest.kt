package org.koaks.node

import kotlinx.coroutines.await
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.ByteString.Companion.encodeUtf8
import org.koaks.framework.model.CheckpointScope
import org.koaks.framework.model.ModelItem
import org.koaks.framework.model.ProviderCheckpoint
import org.koaks.framework.model.ProviderId
import org.koaks.framework.model.ReplayPolicy
import org.koaks.framework.model.TranscriptBasis
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NodeBridgeTest {
    @Test
    fun opaqueItemsAndCheckpointsRoundTrip() {
        val item = ModelItem.ProviderItem(
            providerId = ProviderId("fixture"),
            kind = "future_item",
            displayText = "opaque",
            replay = ReplayPolicy.Preferred,
            payload = "binary".encodeUtf8(),
        )
        assertEquals(item, item.toJson().toModelItem())

        val checkpoint = ProviderCheckpoint(
            providerId = ProviderId("fixture"),
            codecVersion = 2,
            basis = TranscriptBasis(1, "digest"),
            scope = CheckpointScope.InRun,
            payload = "checkpoint".encodeUtf8(),
            expiresAtEpochMs = 1234,
        )
        assertEquals(checkpoint, checkpoint.toJson().toCheckpoint())
    }

    @Test
    fun runtimeLifecycleUsesJsonEnvelopes() = kotlinx.coroutines.test.runTest {
        val bridge = createKoaksBridge(
            configJson = "{}",
            invoke = { _, _ -> Promise.resolve("null") },
            notify = { _, _ -> },
        )
        val metrics = nodeJson.parseToJsonElement(bridge.request("runtime.metrics", "{}").await()).jsonObject
        assertTrue(metrics["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(0, metrics["value"]!!.jsonObject["total"]!!.jsonPrimitive.content.toInt())

        val firstClose = nodeJson.parseToJsonElement(bridge.request("runtime.close", "{}").await()).jsonObject
        val secondClose = nodeJson.parseToJsonElement(bridge.request("runtime.close", "{}").await()).jsonObject
        assertTrue(firstClose["ok"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(secondClose["ok"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun runtimeIpcRejectsUnknownTargetsWithTypedError() = kotlinx.coroutines.test.runTest {
        val bridge = createKoaksBridge(
            configJson = "{}",
            invoke = { _, _ -> Promise.resolve("null") },
            notify = { _, _ -> },
        )
        val response = nodeJson.parseToJsonElement(
            bridge.request(
                "runtime.ipc.send",
                """{"to_run_id":"999","type":"approval","payload":"allow"}""",
            ).await(),
        ).jsonObject

        assertEquals(false, response["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(
            "ipc_target_unavailable",
            response["error"]!!.jsonObject["type"]!!.jsonPrimitive.content,
        )
        bridge.request("runtime.close", "{}").await()
    }
}
