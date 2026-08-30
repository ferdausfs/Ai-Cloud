package dev.repochat.ui.chat.markdown

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Renders a full chat message body: prose via [MarkdownProse], fenced blocks
 * via [CodeBlock]. Used by AI bubbles and anywhere else we show markdown text.
 */
@Composable
fun MarkdownMessageContent(
    text: String,
    modifier: Modifier = Modifier,
    contentColor: Color,
    isOnPrimary: Boolean = false,
    /** Prefer treating the whole body as a single code card (CI logs, dumps). */
    forceCodeLanguage: String? = null,
) {
    val segments = remember(text, forceCodeLanguage) {
        when {
            forceCodeLanguage != null && !text.contains("```") ->
                listOf(MessageSegment.Code(forceCodeLanguage, text.trimEnd()))
            else -> parseMessageSegments(text)
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        segments.forEach { segment ->
            when (segment) {
                is MessageSegment.Prose -> MarkdownProse(
                    markdown = segment.markdown,
                    contentColor = contentColor,
                    isOnPrimary = isOnPrimary,
                )
                is MessageSegment.Code -> CodeBlock(
                    code = segment.code,
                    language = segment.language ?: forceCodeLanguage,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** Heuristic: auto-fix / CI status dumps that should open as a log code card. */
fun preferLogCodeBlock(text: String): Boolean {
    if (text.contains("```")) return false
    val lower = text.lowercase()
    val logHints = listOf(
        "---- log (tail) ----",
        "failed steps:",
        "ci failed",
        "last ci error",
        "unresolved reference",
        "process completed with exit code",
        "e: file://",
    )
    return logHints.any { lower.contains(it) } && text.lines().size >= 4
}
