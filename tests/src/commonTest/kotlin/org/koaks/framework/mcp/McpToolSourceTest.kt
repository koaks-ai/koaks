package org.koaks.framework.mcp

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.koaks.framework.loop.AgentEvent
import org.koaks.framework.loop.FakeLanguageModel
import org.koaks.framework.loop.agent
import org.koaks.framework.mcp.entity.McpTool
import org.koaks.framework.model.ModelEvent
import org.koaks.framework.model.ToolCall
import org.koaks.framework.model.Usage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class McpToolSourceTest {

    /** A fake MCP server exposing one tool; records call count for discover-once assertions. */
    private class FakeGateway : McpToolGateway {
        var listCalls = 0
        var lastArgs: String? = null
        override suspend fun listTools(): List<McpTool> {
            listCalls++
            return listOf(
                McpTool(
                    name = "remote_add",
                    description = "adds two numbers",
                    inputSchema = buildJsonObject {
                        put("type", JsonPrimitive("object"))
                    },
                )
            )
        }
        override suspend fun callTool(name: String, argumentsJson: String): String {
            lastArgs = argumentsJson
            return "42"
        }
    }

    @Test
    fun discovers_mcp_tools_lazily_and_invokes_them() = runTest {
        val gateway = FakeGateway()
        // Step 1: model calls the remote tool. Step 2: finishes.
        val model = FakeLanguageModel(
            listOf(
                ModelEvent.ToolCallCompleted(ToolCall("c1", "remote_add", "{\"a\":1,\"b\":2}")),
                ModelEvent.Completed(Usage.ZERO),
            ),
            listOf(ModelEvent.TextDelta("the sum is 42"), ModelEvent.Completed(Usage.ZERO)),
        )
        val a = agent {
            name = "t"
            model { custom(model) }
            tools { mcp(gateway) }
            terminateAfter(maxSteps = 5)
        }

        val events = a.stream("add 1 and 2").toList()

        // The discovered tool was invoked with the raw JSON args (passthrough, not re-decoded).
        assertEquals("{\"a\":1,\"b\":2}", gateway.lastArgs)
        val toolResult = events.filterIsInstance<AgentEvent.ToolResult>().single()
        assertEquals("42", toolResult.output)
        assertTrue(events.any { it is AgentEvent.Finished })
    }

    @Test
    fun resolves_sources_only_once_across_runs() = runTest {
        val gateway = FakeGateway()
        val model = FakeLanguageModel(
            listOf(ModelEvent.TextDelta("ok"), ModelEvent.Completed(Usage.ZERO)),
            listOf(ModelEvent.TextDelta("ok2"), ModelEvent.Completed(Usage.ZERO)),
        )
        val a = agent {
            name = "t"
            model { custom(model) }
            tools { mcp(gateway) }
        }

        a.stream("hi").toList()
        a.stream("again").toList()

        assertEquals(1, gateway.listCalls, "tools/list must be called exactly once and cached")
    }
}
