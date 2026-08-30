package dev.repochat.ui.chat.markdown

/**
 * A slice of a chat message body. Fenced code blocks are isolated so each can
 * render as its own [dev.repochat.ui.chat.markdown.CodeBlock] with a copy button;
 * everything else stays as markdown prose.
 */
sealed interface MessageSegment {
    data class Prose(val markdown: String) : MessageSegment
    data class Code(val language: String?, val code: String) : MessageSegment
}

private val FENCE_OPEN = Regex("^\\s*```([\\w.+#-]*)\\s*$")
private val FENCE_CLOSE = Regex("^\\s*```\\s*$")

/**
 * Split [raw] on fenced code blocks (` ```lang ` … ` ``` `).
 * Unclosed fences leave the remainder as a code segment (best-effort).
 */
fun parseMessageSegments(raw: String): List<MessageSegment> {
    if (raw.isEmpty()) return emptyList()
    val lines = raw.split('\n')
    val out = mutableListOf<MessageSegment>()
    val prose = StringBuilder()
    var i = 0
    while (i < lines.size) {
        val open = FENCE_OPEN.matchEntire(lines[i])
        if (open == null) {
            if (prose.isNotEmpty()) prose.append('\n')
            prose.append(lines[i])
            i++
            continue
        }
        flushProse(prose, out)
        val language = open.groupValues[1].takeIf { it.isNotBlank() }
        i++
        val code = StringBuilder()
        var closed = false
        while (i < lines.size) {
            if (FENCE_CLOSE.matchEntire(lines[i]) != null) {
                closed = true
                i++
                break
            }
            if (code.isNotEmpty()) code.append('\n')
            code.append(lines[i])
            i++
        }
        out += MessageSegment.Code(language = language, code = code.toString())
        if (!closed) {
            // Unterminated fence — remaining lines already consumed into code.
            break
        }
    }
    flushProse(prose, out)
    return out.ifEmpty { listOf(MessageSegment.Prose(raw)) }
}

private fun flushProse(prose: StringBuilder, out: MutableList<MessageSegment>) {
    val text = prose.toString().trimEnd()
    prose.clear()
    if (text.isNotBlank()) {
        out += MessageSegment.Prose(text)
    }
}

/** True when [text] looks like a fenced log / code dump (e.g. CI excerpts). */
fun looksLikeCodeHeavy(text: String): Boolean {
    if (text.contains("```")) return true
    val lines = text.lineSequence().toList()
    if (lines.size <= 20) return false
    return lines.take(30).count { it.startsWith("e:") || it.contains("FAILED") } >= 2
}
