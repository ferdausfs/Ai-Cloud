package dev.repochat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageTest {

    @Test
    fun `estimator handles blank and short text`() {
        assertEquals(0L, TokenEstimator.estimate(""))
        assertEquals(0L, TokenEstimator.estimate("   \n "))
        assertEquals(1L, TokenEstimator.estimate("hi"))
    }

    @Test
    fun `estimator uses chars-over-four heuristic`() {
        assertEquals(25L, TokenEstimator.estimate("a".repeat(100)))
    }

    @Test
    fun `estimateMessages sums inputs and reply as output`() {
        val usage = TokenEstimator.estimateMessages(
            messages = listOf(
                OllamaMessage(OllamaRole.SYSTEM, "s".repeat(40)),
                OllamaMessage(OllamaRole.USER, "u".repeat(80)),
            ),
            reply = "r".repeat(60),
        )
        assertEquals(10L + 20L, usage.inputTokens)
        assertEquals(15L, usage.outputTokens)
        assertTrue(!usage.reported)
    }

    @Test
    fun `csv escapes commas quotes and newlines`() {
        val csv = UsageCsv.build(
            listOf(
                UsageEvent(
                    timestampMillis = 1_700_000_000_000L,
                    provider = "Groq, \"free\"",
                    model = "model\na",
                    kind = UsageKind.CHAT,
                    inputTokens = 120L,
                    outputTokens = 30L,
                    reported = true,
                ),
            ),
        )
        val lines = csv.trim().split('\n')
        // Header + one row; the escaped newline inside a quoted field keeps
        // the row RFC-4180-valid but naive splitting shows it as 3 segments.
        assertTrue(lines[0].startsWith("timestamp,provider,model,kind"))
        assertTrue(csv.contains("\"Groq, \"\"free\"\"\""))
        assertTrue(csv.contains("\"model\na\""))
        assertTrue(csv.trim().endsWith(",150,yes"))
    }

    @Test
    fun `csv marks estimated rows`() {
        val csv = UsageCsv.build(
            listOf(
                UsageEvent(0L, "Ollama", "m", UsageKind.CHAT, 5, 5, reported = false),
            ),
        )
        assertTrue(csv.trim().endsWith(",10,estimated"))
    }

    @Test
    fun `usage clock start of today truncates to midnight`() {
        // 2026-09-17 15:30:45.123 device-local.
        val cal = java.util.Calendar.getInstance().apply {
            set(2026, java.util.Calendar.SEPTEMBER, 17, 15, 30, 45)
            set(java.util.Calendar.MILLISECOND, 123)
        }
        val start = UsageClock.startOfToday(cal.timeInMillis)
        val startCal = java.util.Calendar.getInstance().apply { timeInMillis = start }
        assertEquals(0, startCal.get(java.util.Calendar.HOUR_OF_DAY))
        assertEquals(0, startCal.get(java.util.Calendar.MINUTE))
        assertEquals(0, startCal.get(java.util.Calendar.SECOND))
        assertEquals(0, startCal.get(java.util.Calendar.MILLISECOND))
        assertEquals(17, startCal.get(java.util.Calendar.DAY_OF_MONTH))
        // DaysAgo shifts by whole days.
        assertEquals(start - 7L * 86_400_000L, UsageClock.startOfDaysAgo(7, cal.timeInMillis))
    }
}
