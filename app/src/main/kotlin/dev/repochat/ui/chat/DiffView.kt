package dev.repochat.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.repochat.core.model.DiffLine
import dev.repochat.core.model.DiffLineType
import dev.repochat.core.model.LineDiffer
import dev.repochat.ui.theme.CodeTextStyle
import dev.repochat.ui.theme.DiffPalette
import dev.repochat.ui.theme.diffPalette

private const val MAX_RENDERED_LINES = 400

/**
 * Line-based diff view with readable, color-coded additions/removals.
 * Gutter numbers + monospace text, capped at [MAX_RENDERED_LINES] lines.
 */
@Composable
fun DiffView(
    oldText: String,
    newText: String,
    modifier: Modifier = Modifier,
) {
    val palette = diffPalette()
    val diff = remember(oldText, newText) { LineDiffer.diff(oldText, newText) }
    val shown = diff.lines.take(MAX_RENDERED_LINES)

    Box(modifier = modifier) {
        SelectionContainer {
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .background(MaterialTheme.colorScheme.surface),
            ) {
                Column {
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
