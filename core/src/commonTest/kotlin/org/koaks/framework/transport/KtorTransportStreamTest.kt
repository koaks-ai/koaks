package org.koaks.framework.transport

import io.ktor.utils.io.ByteChannel
import kotlinx.coroutines.test.runTest
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
}
