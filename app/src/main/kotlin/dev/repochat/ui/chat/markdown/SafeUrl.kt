package dev.repochat.ui.chat.markdown

/**
 * Allowlist for URLs rendered inside chat messages (audit BUG-204).
 *
 * Markdown link targets come from model output, which itself reflects
 * untrusted repository file content and CI logs (an indirect prompt-injection
 * channel). Only plain http(s) links with a host are ever handed to the
 * platform URL handler; everything else (`intent://`, `javascript:`,
 * `file://`, custom app schemes, blank hosts) renders but does not open.
 *
 * Pure JVM (java.net.URI) so it is directly unit-testable without Robolectric.
 */
fun isSafeBrowseUrl(url: String): Boolean {
    val trimmed = url.trim()
    if (trimmed.isEmpty() || trimmed.length > 2048) return false
    // Fast reject on scheme before URI parsing (avoids URI's odd fallbacks).
    val scheme = trimmed.substringBefore(':', missingDelimiterValue = "").lowercase()
    if (scheme != "http" && scheme != "https") return false
    return try {
        val uri = java.net.URI(trimmed)
        val host = uri.host
        uri.scheme?.lowercase() in setOf("http", "https") && !host.isNullOrBlank()
    } catch (_: Exception) {
        false
    }
}
