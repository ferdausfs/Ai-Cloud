package dev.repochat.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.repochat.R
import dev.repochat.core.model.DiffLine
import dev.repochat.core.model.DiffLineType
import dev.repochat.core.model.LineDiffer
import dev.repochat.ui.theme.CodeTextStyle
import dev.repochat.ui.theme.DiffPalette
import dev.repochat.ui.theme.diffPalette
import kotlinx.coroutines.delay

private const val MAX_RENDERED_LINES = 400
private const val COLLAPSE_LINE_THRESHOLD = 15
private val COLLAPSED_MAX_HEIGHT = 220.dp

/**
 * Line-based diff view wrapped in the same card chrome as [dev.repochat.ui.chat.markdown.CodeBlock]
 * (language label, copy of the *new* file content, collapse for long diffs).
 */
@Composable
fun DiffView(
    oldText: String,
    newText: String,
    modifier: Modifier = Modifier,
    language: String? = null,
) {
    val palette = diffPalette()
    val scheme = MaterialTheme.colorScheme
    val diff = remember(oldText, newText) { LineDiffer.diff(oldText, newText) }
    val shown = diff.lines.take(MAX_RENDERED_LINES)
    val shape = RoundedCornerShape(12.dp)
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    val canCollapse = shown.size > COLLAPSE_LINE_THRESHOLD
    var expanded by remember(oldText, newText) { mutableStateOf(!canCollapse) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1_500)
            copied = false
        }
    }

    val label = language?.takeIf { it.isNotBlank() }?.lowercase()
        ?: stringResource(R.string.chat_code_default_lang)

    Surface(
        shape = shape,
        color = scheme.surfaceVariant,
        contentColor = scheme.onSurfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, scheme.outlineVariant.copy(alpha = 0.6f), shape),
    ) {
        Column(modifier = Modifier.animateContentSize(tween(220, easing = FastOutSlowInEasing))) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(scheme.surface.copy(alpha = 0.55f))
                    .padding(start = 12.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = scheme.onSurfaceVariant,
                )
                IconButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(newText))
                        copied = true
                    },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                        contentDescription = stringResource(
                            if (copied) R.string.chat_code_copied else R.string.chat_code_copy,
                        ),
                        tint = if (copied) scheme.secondary else scheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (!expanded && canCollapse) {
                            Modifier.heightIn(max = COLLAPSED_MAX_HEIGHT)
                        } else {
                            Modifier
                        },
                    ),
            ) {
                SelectionContainer {
                    val hScroll = rememberScrollState()
                    val vScroll = rememberScrollState()
                    Column(
                        modifier = Modifier
                            .horizontalScroll(hScroll)
                            .then(
                                if (!expanded && canCollapse) Modifier.verticalScroll(vScroll)
                                else Modifier,
                            )
                            .background(scheme.surfaceVariant),
                    ) {
                        shown.forEach { line -> DiffRow(line = line, palette = palette) }
                        if (diff.lines.size > MAX_RENDERED_LINES) {
                            Text(
                                text = "… ${diff.lines.size - MAX_RENDERED_LINES} more lines hidden",
                                style = CodeTextStyle,
                                color = palette.contextText,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }

            if (canCollapse) {
                TextButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(bottom = 4.dp),
                ) {
                    Icon(
                        imageVector = if (expanded) {
                            Icons.Rounded.KeyboardArrowUp
                        } else {
                            Icons.Rounded.KeyboardArrowDown
                        },
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = stringResource(
                            if (expanded) R.string.chat_code_show_less else R.string.chat_code_show_more,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun DiffRow(line: DiffLine, palette: DiffPalette) {
    val background = when (line.type) {
        DiffLineType.ADD -> palette.addBackground
        DiffLineType.REMOVE -> palette.removeBackground
        DiffLineType.CONTEXT -> Color.Transparent
    }
    val textColor = when (line.type) {
        DiffLineType.ADD -> palette.addText
        DiffLineType.REMOVE -> palette.removeText
        DiffLineType.CONTEXT -> palette.contextText
    }
    val sign = when (line.type) {
        DiffLineType.ADD -> "+"
        DiffLineType.REMOVE -> "-"
        DiffLineType.CONTEXT -> " "
    }

    Row(
        modifier = Modifier.background(background),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Gutter(text = line.oldLine?.toString().orEmpty(), palette = palette)
        Gutter(text = line.newLine?.toString().orEmpty(), palette = palette)
        Text(
            text = sign,
            style = CodeTextStyle,
            color = textColor,
            modifier = Modifier.width(14.dp),
            textAlign = TextAlign.Center,
        )
        Text(
            text = line.text.ifEmpty { " " },
            style = CodeTextStyle,
            color = textColor,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 1.dp),
        )
    }
}

@Composable
private fun Gutter(text: String, palette: DiffPalette) {
    Text(
        text = text,
        style = CodeTextStyle,
        color = palette.contextText.copy(alpha = 0.75f),
        textAlign = TextAlign.End,
        modifier = Modifier
            .width(36.dp)
            .padding(end = 6.dp),
    )
}
