package org.koaks.framework.transport

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.koaks.framework.provider.RetryBudget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class KtorTransportTest {

    private fun sseBody(vararg payloads: String): ByteReadChannel {
        val text = payloads.joinToString("\n") { "data: $it" } + "\n\n"
        return ByteReadChannel(text)
    }

    private fun call(url: String = "http://test/chat", retry: RetryBudget = RetryBudget(maxRetries = 3, initialBackoffMs = 1)) =
        WireCall(
            method = HttpMethod.POST,
            url = url,
            body = """{"q":"hi"}""",
            expect = Framing.Sse,
            idempotencyKey = "key-1",
            retry = retry,
        )

    @Test
    fun retries_transparently_before_first_byte() = runTest {
        var calls = 0
        val engine = MockEngine { _ ->
            calls++
            if (calls < 3) respondError(HttpStatusCode.ServiceUnavailable)
            else respond(
                sseBody("""{"a":"ok"}"""),
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
            )
        }
        val transport = KtorTransport(HttpClient(engine) { install(HttpTimeout) })
        val frames = transport.call(call()).toList()
        assertEquals(3, calls)
        assertTrue(frames.any { it is WireFrame.Sse && it.data.contains("ok") })
        transport.close()
    }

    @Test
    fun gives_up_after_retry_budget_exhausted() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            respondError(HttpStatusCode.InternalServerError)
        }
        val transport = KtorTransport(HttpClient(engine) { install(HttpTimeout) })
        assertFailsWith<TransportException> {
            transport.call(call(retry = RetryBudget(maxRetries = 2, initialBackoffMs = 1))).toList()
        }
        assertEquals(3, calls)
        transport.close()
    }

    @Test
    fun client_errors_are_http_error_frames_without_retry() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            respondError(HttpStatusCode.NotFound)
        }
        val transport = KtorTransport(HttpClient(engine) { install(HttpTimeout) })
        val frames = transport.call(call(retry = RetryBudget(maxRetries = 2, initialBackoffMs = 1))).toList()
        assertEquals(1, calls)
        val error = assertIs<WireFrame.HttpError>(frames.single())
        assertEquals(404, error.status)
        transport.close()
    }

    @Test
    fun forwards_sse_payloads_including_done_marker() = runTest {
        val engine = MockEngine {
            respond(
                ByteReadChannel("data: {\"a\":\"first\"}\n\ndata: [DONE]\n\n"),
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
            )
        }
        val transport = KtorTransport(HttpClient(engine) { install(HttpTimeout) })
        val frames = transport.call(call(retry = RetryBudget(maxRetries = 0))).toList().filterIsInstance<WireFrame.Sse>()
        assertEquals(listOf("""{"a":"first"}""", "[DONE]"), frames.map { it.data })
        transport.close()
    }

    @Test
    fun sends_idempotency_key_header() = runTest {
        var seen: String? = null
        val engine = MockEngine { request ->
            seen = request.headers["Idempotency-Key"]
            respond("{}", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val transport = KtorTransport(HttpClient(engine) { install(HttpTimeout) })
        transport.call(
            WireCall(
                method = HttpMethod.POST,
                url = "http://test/chat",
                body = "{}",
                expect = Framing.Json,
                idempotencyKey = "abc",
                retry = RetryBudget(maxRetries = 0),
            ),
        ).toList()
        assertEquals("abc", seen)
        transport.close()
    }
}
