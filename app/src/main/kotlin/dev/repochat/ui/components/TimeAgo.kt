package dev.repochat.ui.components

/** Compact relative-time formatting for repo cards. */
fun timeAgo(millis: Long, now: Long = System.currentTimeMillis()): String {
    val diff = (now - millis).coerceAtLeast(0L)
    return when {
        diff < 60_000L -> "just now"
        diff < 3_600_000L -> "${diff / 60_000L}m ago"
        diff < 86_400_000L -> "${diff / 3_600_000L}h ago"
        diff < 7L * 86_400_000L -> "${diff / 86_400_000L}d ago"
        else -> "${diff / (7L * 86_400_000L)}w ago"
    }
}
