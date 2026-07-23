package org.koaks.cli.app

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class CrashReporterTest {
    @Test
    fun formatsFatalHeaderWithTypeAndMessage() {
        val report = formatCrashReport(IllegalStateException("boom"))

        assertContains(report, "[fatal] IllegalStateException: boom")
        assertTrue(report.lines().size > 1)
    }

    @Test
    fun includesCauseChainInStackTrace() {
        val cause = IllegalArgumentException("root cause")
        val report = formatCrashReport(RuntimeException("wrapper", cause))

        assertContains(report, "[fatal] RuntimeException: wrapper")
        assertContains(report, "IllegalArgumentException")
        assertContains(report, "root cause")
    }

    @Test
    fun formatsFatalHeaderWithoutMessage() {
        val report = formatCrashReport(NullPointerException())

        assertContains(report, "[fatal] NullPointerException")
        assertTrue(!report.startsWith("[fatal] NullPointerException:"))
    }
}
