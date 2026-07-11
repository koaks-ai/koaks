package org.koaks.framework.transport

import io.ktor.utils.io.ByteChannel
import kotlinx.coroutines.test.runTest
import org.koaks.framework.provider.ModelConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KtorTransportStreamTest {
    @Test
    fun failsWhenAnOpenStreamProducesNoLineWithinIdleBudget() = runTest {
        val channel = ByteChannel()

        val error = assertFailsWith<StreamIdleTimeoutException> {
            readStreamLine(channel, idleTimeoutMs = 100)
        }

        assertEquals(100, error.idleTimeoutMs)
        channel.close()
    }

    @Test
    fun distinguishesCleanEofFromIdleTimeout() = runTest {
        val channel = ByteChannel().apply { close() }

        assertEquals(null, readStreamLine(channel, idleTimeoutMs = 100))
    }

    @Test
    fun rejectsEofWhenProviderRequiresAnEndMarker() {
        val config = ModelConfig(
            baseUrl = "https://example.test/v1/chat/completions",
            modelName = "test",
            requireStreamEndMarker = true,
        )

        assertFailsWith<TransportException> {
            validateStreamEnd(config, endMarkerReceived = false)
        }
    }

    @Test
    fun acceptsTheRequiredEndMarker() {
        val config = ModelConfig(
            baseUrl = "https://example.test/v1/chat/completions",
            modelName = "test",
            requireStreamEndMarker = true,
        )

        validateStreamEnd(config, endMarkerReceived = true)
    }
}
