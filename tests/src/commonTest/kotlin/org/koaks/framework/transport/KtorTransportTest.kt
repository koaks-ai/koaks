package org.koaks.framework.transport

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import org.koaks.framework.provider.ModelConfig
import org.koaks.framework.provider.RetryBudget
import org.koaks.framework.provider.WireAdapter
import org.koaks.framework.utils.json.JsonUtil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@Serializable
private data class Req(val q: String)

@Serializable
private data class Resp(val a: String)

class KtorTransportTest {

    private val adapter = WireAdapter(Req.serializer(), Resp.serializer())

    private fun sseBody(vararg objs: Resp): ByteReadChannel {
        val text = objs.joinToString("\n") { "data: ${JsonUtil.toJson(it, Resp.serializer())}" } + "\ndata: [DONE]\n"
        return ByteReadChannel(text)
    }

    @Test
    fun retries_transparently_before_first_byte() = runTest {
        var calls = 0
        val engine = MockEngine { _ ->
            calls++
            if (calls < 3) respondError(HttpStatusCode.ServiceUnavailable)
            else respond(
                sseBody(Resp("ok")),
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
            )
        }
        val transport = KtorTransport(HttpClient(engine) { install(HttpTimeout) })
        val config = ModelConfig(
            baseUrl = "http://test/chat", modelName = "m",
            retry = RetryBudget(maxRetries = 3, initialBackoffMs = 1),
        )

        val chunks = transport.stream(config, Req("hi"), adapter).toList()

        assertEquals(3, calls, "should retry twice then succeed on the 3rd attempt")
        assertEquals(listOf(Resp("ok")), chunks)
        transport.close()
    }

    @Test
    fun gives_up_after_retry_budget_exhausted() = runTest {
        var calls = 0
        val engine = MockEngine { _ ->
            calls++
            respondError(HttpStatusCode.InternalServerError)
        }
        val transport = KtorTransport(HttpClient(engine) { install(HttpTimeout) })
        val config = ModelConfig(
            baseUrl = "http://test/chat", modelName = "m",
            retry = RetryBudget(maxRetries = 2, initialBackoffMs = 1),
        )

        assertFailsWith<TransportException> {
            transport.stream(config, Req("hi"), adapter).toList()
        }
        // maxRetries=2 means 1 initial + 2 retries = 3 attempts.
        assertEquals(3, calls)
        transport.close()
    }

    @Test
    fun does_not_retry_after_first_chunk_emitted() = runTest {
        var calls = 0
        val engine = MockEngine { _ ->
            calls++
            // Stream one valid chunk, then a truncated/garbage line that fails to decode.
            val text = "data: ${JsonUtil.toJson(Resp("first"), Resp.serializer())}\n" +
                "data: {not valid json}\n"
            respond(
                ByteReadChannel(text),
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
            )
        }
        val transport = KtorTransport(HttpClient(engine) { install(HttpTimeout) })
        val config = ModelConfig(
            baseUrl = "http://test/chat", modelName = "m",
            retry = RetryBudget(maxRetries = 5, initialBackoffMs = 1),
        )

        val collected = mutableListOf<Resp>()
        assertFailsWith<Throwable> {
            transport.stream(config, Req("hi"), adapter).collect { collected += it }
        }
        // The failure came AFTER the first chunk was emitted → no retry.
        assertEquals(1, calls, "must not retry once any byte was forwarded downstream")
        assertEquals(listOf(Resp("first")), collected)
        transport.close()
    }
}
