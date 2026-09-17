package dev.repochat.core.model

/**
 * Token usage of one LLM completion. [reported] is true when the provider
 * itself returned token counts; false when the app estimated them from text
 * length (chars/4 heuristic) because the provider did not report usage.
 */
data class LlmUsage(
    val inputTokens: Long,
    val outputTokens: Long,
    val reported: Boolean,
)

/** What kind of provider call produced a [UsageEvent]. */
enum class UsageKind { CHAT, IMAGE, SPEECH }

/** One persisted metering row — a single provider call. */
data class UsageEvent(
    val timestampMillis: Long,
    val provider: String,
    val model: String,
    val kind: UsageKind,
    val inputTokens: Long,
    val outputTokens: Long,
    /** True when token counts came from the provider, false when estimated. */
    val reported: Boolean,
) {
    val totalTokens: Long get() = inputTokens + outputTokens
}

/** Aggregate token totals over a time range. */
data class UsageTotals(
    val requests: Long = 0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
) {
    val totalTokens: Long get() = inputTokens + outputTokens
}

/** Per-provider breakdown row for the usage dashboard. */
data class UsageProviderTotals(
    val provider: String,
    val requests: Long = 0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
) {
    val totalTokens: Long get() = inputTokens + outputTokens
}

/**
 * Token-count estimation for providers that do not report usage. The ~4
 * chars-per-token heuristic is intentionally simple and honest: the UI marks
 * estimated numbers with "≈" so they are never confused with exact counts.
 */
object TokenEstimator {

    /** Estimated token count for one text blob (0 for blank input). */
    fun estimate(text: String): Long {
        val t = text.trim()
        if (t.isEmpty()) return 0L
        return (t.length / 4L).coerceAtLeast(1L)
    }

    /**
     * Estimated input tokens for a whole message list plus the reply as
     * output. Used when a provider reports no usage at all.
     */
    fun estimateMessages(messages: List<OllamaMessage>, reply: String): LlmUsage {
        val input = messages.sumOf { estimate(it.content) }
        return LlmUsage(inputTokens = input, outputTokens = estimate(reply), reported = false)
    }
}

/** Millisecond-of-day helpers for the daily budget window (device-local day). */
object UsageClock {

    /** Start of the current device-local day, in epoch millis. */
    fun startOfToday(nowMillis: Long = System.currentTimeMillis()): Long {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = nowMillis
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    fun startOfDaysAgo(days: Int, nowMillis: Long = System.currentTimeMillis()): Long =
        startOfToday(nowMillis) - days * 24L * 60L * 60L * 1000L
}

/** Minimal CSV export for expense/tax tracking (RFC-4180 quoting). */
object UsageCsv {

    private fun field(raw: String): String =
        if (raw.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"${raw.replace("\"", "\"\"")}\""
        } else {
            raw
        }

    fun build(events: List<UsageEvent>): String = buildString {
        append("timestamp,provider,model,kind,input_tokens,output_tokens,total_tokens,reported")
        append('\n')
        events.forEach { e ->
            append(e.timestampMillis).append(',')
            append(field(e.provider)).append(',')
            append(field(e.model)).append(',')
            append(e.kind.name).append(',')
            append(e.inputTokens).append(',')
            append(e.outputTokens).append(',')
            append(e.totalTokens).append(',')
            append(if (e.reported) "yes" else "estimated")
            append('\n')
        }
    }
}
