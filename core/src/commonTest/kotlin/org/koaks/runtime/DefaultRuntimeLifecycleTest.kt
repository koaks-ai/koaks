package org.koaks.runtime

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class DefaultRuntimeLifecycleTest {

    @AfterTest
    fun restoreDefault() {
        AgentRuntime.resetDefaultForTesting()
    }

    @Test
    fun default_is_configurable_only_before_lazy_initialization() {
        AgentRuntime.resetDefaultForTesting()
        AgentRuntime.configureDefault { maxConcurrency = 3 }

        val first = AgentRuntime.default

        assertEquals(3, first.maxConcurrency)
        assertSame(first, AgentRuntime.default)
        assertFailsWith<IllegalStateException> {
            AgentRuntime.configureDefault { maxConcurrency = 4 }
        }
    }

    @Test
    fun shutdown_is_idempotent_and_does_not_recreate_the_default() {
        AgentRuntime.resetDefaultForTesting()
        AgentRuntime.default

        AgentRuntime.shutdownDefault()
        AgentRuntime.shutdownDefault()

        assertFailsWith<IllegalStateException> { AgentRuntime.default }
    }

    @Test
    fun tests_can_override_and_reset_the_default_without_exposing_a_production_mutator() {
        val replacement = AgentRuntime { maxConcurrency = 7 }
        AgentRuntime.overrideDefaultForTesting(replacement)

        assertSame(replacement, AgentRuntime.default)

        AgentRuntime.resetDefaultForTesting { maxConcurrency = 2 }
        assertEquals(2, AgentRuntime.default.maxConcurrency)
    }
}
