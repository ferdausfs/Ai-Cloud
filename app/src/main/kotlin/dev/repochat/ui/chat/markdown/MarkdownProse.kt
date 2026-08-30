package dev.repochat.ui.chat.markdown

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Lightweight Compose-native markdown for chat prose: headings, bold/italic,
 * inline code, links, bullet/numbered lists. Fenced blocks are handled upstream
 * by [parseMessageSegments] so each gets its own copy button.
 */
@Composable
fun MarkdownProse(
    markdown: String,
    modifier: Modifier = Modifier,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    isOnPrimary: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val linkColor = if (isOnPrimary) contentColor else scheme.primary
    val inlineCodeBg = if (isOnPrimary) {
        contentColor.copy(alpha = 0.18f)
    } else {
        scheme.surfaceVariant
    }
    val inlineCodeFg = if (isOnPrimary) contentColor else scheme.onSurfaceVariant
    val bodyStyle = MaterialTheme.typography.bodyLarge.copy(color = contentColor)
    val blocks = remember(markdown) { splitProseBlocks(markdown) }

    SelectionContainer {
        Column(modifier = modifier.fillMaxWidth()) {
            blocks.forEach { block ->
                when (block) {
                    is ProseBlock.Heading -> {
                        val style = when (block.level) {
                            1 -> MaterialTheme.typography.titleLarge
                            2 -> MaterialTheme.typography.titleMedium
                            else -> MaterialTheme.typography.titleSmall
                        }.copy(color = contentColor, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = block.text,
                            style = style,
                            modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                        )
                    }
                    is ProseBlock.Bullet -> {
                        val annotated = remember(block.text, contentColor, linkColor, inlineCodeBg, inlineCodeFg) {
                            annotateInlineMarkdown(
                                text = "•  " + block.text,
                                baseColor = contentColor,
                                linkColor = linkColor,
                                inlineCodeBg = inlineCodeBg,
                                inlineCodeFg = inlineCodeFg,
                            )
                        }
                        Text(
                            text = annotated,
                            style = bodyStyle,
                            modifier = Modifier.padding(vertical = 1.dp),
                        )
                    }
                    is ProseBlock.Numbered -> {
                        val annotated = remember(block.text, block.number, contentColor, linkColor, inlineCodeBg, inlineCodeFg) {
                            annotateInlineMarkdown(
                                text = "${block.number}.  " + block.text,
                                baseColor = contentColor,
                                linkColor = linkColor,
                                inlineCodeBg = inlineCodeBg,
                                inlineCodeFg = inlineCodeFg,
                            )
                        }
                        Text(
                            text = annotated,
                            style = bodyStyle,
                            modifier = Modifier.padding(vertical = 1.dp),
                        )
                    }
                    is ProseBlock.Paragraph -> {
                        val annotated = remember(block.text, contentColor, linkColor, inlineCodeBg, inlineCodeFg) {
                            annotateInlineMarkdown(
                                text = block.text,
                                baseColor = contentColor,
                                linkColor = linkColor,
                                inlineCodeBg = inlineCodeBg,
                                inlineCodeFg = inlineCodeFg,
                            )
                        }
                        Text(
                            text = annotated,
                            style = bodyStyle,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

private sealed interface ProseBlock {
    data class Heading(val level: Int, val text: String) : ProseBlock
    data class Bullet(val text: String) : ProseBlock
    data class Numbered(val number: Int, val text: String) : ProseBlock
    data class Paragraph(val text: String) : ProseBlock
}

internal fun splitProseBlocks(markdown: String): List<ProseBlock> {
    val lines = markdown.replace("\r\n", "\n").split('\n')
    val out = mutableListOf<ProseBlock>()
    val para = StringBuilder()
    fun flushPara() {
        val t = para.toString().trim()
        para.clear()
        if (t.isNotEmpty()) out += ProseBlock.Paragraph(t)
    }
    for (raw in lines) {
        val line = raw.trimEnd()
        val trimmed = line.trimStart()
        when {
            trimmed.isEmpty() -> flushPara()
            trimmed.startsWith("### ") -> {
                flushPara()
                out += ProseBlock.Heading(3, trimmed.removePrefix("### ").trim())
            }
            trimmed.startsWith("## ") -> {
                flushPara()
                out += ProseBlock.Heading(2, trimmed.removePrefix("## ").trim())
            }
            trimmed.startsWith("# ") -> {
                flushPara()
                out += ProseBlock.Heading(1, trimmed.removePrefix("# ").trim())
            }
            trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                flushPara()
                out += ProseBlock.Bullet(trimmed.drop(2).trim())
            }
            trimmed.matches(Regex("^\\d+\\.\\s+.*")) -> {
                flushPara()
                val num = trimmed.substringBefore('.').toIntOrNull() ?: 1
                out += ProseBlock.Numbered(num, trimmed.substringAfter('.').trim())
            }
            else -> {
                if (para.isNotEmpty()) para.append(' ')
                para.append(trimmed)
            }
        }
    }
    flushPara()
    return out
}

/**
 * Inline spans: `code`, **bold**, *italic*, [label](url).
 * Links use [LinkAnnotation.Url] so Material3 [Text] opens them natively.
 */
internal fun annotateInlineMarkdown(
    text: String,
    baseColor: Color,
    linkColor: Color,
    inlineCodeBg: Color,
    inlineCodeFg: Color,
): androidx.compose.ui.text.AnnotatedString = buildAnnotatedString {
    val base = SpanStyle(color = baseColor)
    val linkStyles = TextLinkStyles(
        style = SpanStyle(
            color = linkColor,
            textDecoration = TextDecoration.Underline,
        ),
    )
    var i = 0
    while (i < text.length) {
        when {
            text.startsWith("`", i) && !text.startsWith("``", i) -> {
                val end = text.indexOf('`', i + 1)
                if (end > i) {
                    val code = text.substring(i + 1, end)
                    withStyle(
                        SpanStyle(
                            color = inlineCodeFg,
                            background = inlineCodeBg,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                        ),
                    ) { append(code) }
                    i = end + 1
                } else {
                    withStyle(base) { append(text[i]) }
                    i++
                }
            }
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end > i) {
                    withStyle(base.copy(fontWeight = FontWeight.Bold)) {
                        append(text.substring(i + 2, end))
                    }
                    i = end + 2
                } else {
                    withStyle(base) { append("**") }
                    i += 2
                }
            }
            text.startsWith("[", i) -> {
                val closeLabel = text.indexOf(']', i + 1)
                if (closeLabel > i && closeLabel + 1 < text.length && text[closeLabel + 1] == '(') {
                    val closeUrl = text.indexOf(')', closeLabel + 2)
                    if (closeUrl > closeLabel) {
                        val label = text.substring(i + 1, closeLabel)
                        val url = text.substring(closeLabel + 2, closeUrl)
                        withLink(LinkAnnotation.Url(url, linkStyles)) {
                            append(label)
                        }
                        i = closeUrl + 1
                    } else {
                        withStyle(base) { append(text[i]) }
                        i++
                    }
                } else {
                    withStyle(base) { append(text[i]) }
                    i++
                }
            }
            text.startsWith("*", i) && !text.startsWith("**", i) -> {
                val end = text.indexOf('*', i + 1)
                if (end > i) {
                    withStyle(base.copy(fontStyle = FontStyle.Italic)) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                } else {
                    withStyle(base) { append(text[i]) }
                    i++
                }
            }
            else -> {
                withStyle(base) { append(text[i]) }
                i++
            }
        }
    }
}
