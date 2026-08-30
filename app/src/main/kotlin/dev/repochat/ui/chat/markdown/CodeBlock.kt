package dev.repochat.ui.chat.markdown

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.repochat.R
import dev.repochat.ui.theme.CodeTextStyle
import kotlinx.coroutines.delay

private const val COLLAPSE_LINE_THRESHOLD = 15
private val COLLAPSED_MAX_HEIGHT = 220.dp

/**
 * ChatGPT/Claude-style fenced code card: language chip, one-tap copy with
 * brief checkmark confirmation, monospace body (horizontally scrollable),
 * optional collapse for long blocks. Theme-derived colors for light/dark.
 */
@Composable
fun CodeBlock(
    code: String,
    language: String? = null,
    modifier: Modifier = Modifier,
    /** When false, never collapses (e.g. short write_file previews). */
    collapsible: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    val lineCount = remember(code) { code.count { it == '\n' } + if (code.isEmpty()) 0 else 1 }
    val canCollapse = collapsible && lineCount > COLLAPSE_LINE_THRESHOLD
    var expanded by remember(code) { mutableStateOf(!canCollapse) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1_500)
            copied = false
        }
    }

    val palette = remember(scheme) {
        SimpleSyntaxHighlighter.Palette(
            plain = scheme.onSurface,
            keyword = scheme.primary,
            string = scheme.secondary,
            comment = scheme.onSurfaceVariant.copy(alpha = 0.85f),
            number = scheme.tertiary,
            type = scheme.primary.copy(alpha = 0.85f),
        )
    }
    val highlighted = remember(code, language, palette) {
        SimpleSyntaxHighlighter.highlight(code, language, palette)
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
            // Header: language + copy
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
                        clipboard.setText(AnnotatedString(code))
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
                    Text(
                        text = highlighted,
                        style = CodeTextStyle.copy(color = scheme.onSurface),
                        modifier = Modifier
                            .horizontalScroll(hScroll)
                            .then(
                                if (!expanded && canCollapse) {
                                    Modifier.verticalScroll(vScroll)
                                } else {
                                    Modifier
                                },
                            )
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    )
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
                        imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
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
