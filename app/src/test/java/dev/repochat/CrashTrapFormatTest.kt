package dev.repochat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashTrapFormatTest {

    @Test
    fun `report contains version, device, thread and stack trace`() {
        val report = CrashTrap.formatReport(
            appVersion = "2.5.2 (versionCode 12)",
            threadName = "main",
            atMillis = 1_700_000_000_000,
            throwable = IllegalStateException("Room cannot verify the data integrity"),
        )

        assertTrue(report.startsWith("Ai Cloud crash report"))
        assertTrue(report.contains("app: 2.5.2 (versionCode 12)"))
        assertTrue(report.contains("thread: main"))
        assertTrue(report.contains("android:"))
        assertTrue(report.contains("device:"))
        assertTrue(report.contains("java.lang.IllegalStateException"))
        assertTrue(report.contains("Room cannot verify the data integrity"))
        assertTrue(report.contains("at dev.repochat.CrashTrapFormatTest"))
    }

    @Test
    fun `oversized stack trace is truncated`() {
        val deep = buildThrowable(depth = 400)
        val report = CrashTrap.formatReport(
            appVersion = "test",
            threadName = "main",
            atMillis = 0,
            throwable = deep,
        )
        // Header + metadata stay intact, and the report never exceeds the
        // cap by a meaningful margin.
        assertTrue(report.contains("Ai Cloud crash report"))
        assertTrue(
            "report should stay bounded",
            report.length < 14_000,
        )
    }

    private fun buildThrowable(depth: Int): Throwable {
        var t: Throwable = IllegalStateException("leaf")
        repeat(depth) {
            t = IllegalStateException("level $it", t)
        }
        return t
    }

    @Test
    fun `null cause chains render without throwing`() {
        val report = CrashTrap.formatReport(
            appVersion = "test",
            threadName = "worker-1",
            atMillis = 0,
            throwable = RuntimeException("boom", null),
        )
        assertTrue(report.contains("java.lang.RuntimeException: boom"))
        assertEquals("worker-1", Regex("thread: (.*)").find(report)!!.groupValues[1])
    }
}
