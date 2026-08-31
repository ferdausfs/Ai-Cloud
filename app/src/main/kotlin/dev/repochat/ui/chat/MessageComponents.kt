package dev.repochat.ui.chat

import android.util.Base64
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.repochat.R
import dev.repochat.core.model.ChatMessage
import dev.repochat.core.model.ChatRole
import dev.repochat.core.model.MessageKind
import dev.repochat.core.model.MessageStatus
import dev.repochat.core.model.PendingChange
import dev.repochat.ui.chat.markdown.CodeBlock
import dev.repochat.ui.chat.markdown.MarkdownMessageContent
import dev.repochat.ui.chat.markdown.preferLogCodeBlock
import dev.repochat.ui.components.InfoChip
import dev.repochat.ui.theme.CodeTextStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Subtle entrance animation: each message slides up and fades in on first
 * composition (keyed by message id).
 */
@Composable
fun Modifier.bubbleIn(key: Any): Modifier {
    val offsetY = remember(key) { Animatable(22f) }
    val alpha = remember(key) { Animatable(0f) }
    LaunchedEffect(key) {
        kotlinx.coroutines.coroutineScope {
            launch {
                offsetY.animateTo(0f, tween(280, easing = FastOutSlowInEasing))
            }
            launch {
                alpha.animateTo(1f, tween(220))
            }
        }
    }
    return this.graphicsLayer {
        translationY = offsetY.value
        this.alpha = alpha.value
    }
}

@Composable
fun MessageItem(
    message: ChatMessage,
    liveChange: PendingChange?,
    gateActive: Boolean,
    committing: Boolean,
    branch: String?,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isUser = message.role == ChatRole.USER
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        when (message.kind) {
            MessageKind.TEXT -> ChatBubble(
                text = message.text.orEmpty(),
                isUser = isUser,
                messageId = message.id,
            )

            MessageKind.READ_FILE -> ReadFileCard(message = message)

            MessageKind.WRITE_FILE -> WriteFileCard(
                message = message,
                liveChange = liveChange,
                gateActive = gateActive,
                committing = committing,
                branch = branch,
                onApprove = onApprove,
                onReject = onReject,
            )
        }
    }
}

@Composable
fun ChatBubble(
    text: String,
    isUser: Boolean,
    messageId: Long = 0L,
    modifier: Modifier = Modifier,
) {
    // HuggingChat-like: soft user tint, plain assistant text.
    val shape = RoundedCornerShape(12.dp)
    val contentColor = MaterialTheme.colorScheme.onSurface
    Surface(
        shape = shape,
        color = if (isUser) {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        } else {
            MaterialTheme.colorScheme.background
        },
        contentColor = contentColor,
        tonalElevation = 0.dp,
        modifier = modifier
            .fillMaxWidth(0.92f)
            .bubbleIn(key = "$messageId-$text".hashCode()),
    ) {
        if (isUser) {
            // User messages stay plain — they are short prompts, not markdown docs.
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        } else {
            val asLog = preferLogCodeBlock(text)
            MarkdownMessageContent(
                text = text,
                contentColor = contentColor,
                isOnPrimary = false,
                forceCodeLanguage = if (asLog) "log" else null,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
fun ReadFileCard(message: ChatMessage, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier
            .fillMaxWidth(0.85f)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
            .bubbleIn(key = "read-${message.id}"),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.MenuBook,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = message.filePath.orEmpty(),
                style = CodeTextStyle,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            InfoChip(
                text = stringResource(R.string.chat_read_bubble),
                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
fun WriteFileCard(
    message: ChatMessage,
    liveChange: PendingChange?,
    gateActive: Boolean,
    committing: Boolean,
    branch: String?,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val decoded = remember(message.base64Content) {
        message.base64Content?.let { encoded ->
            try {
                String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }
    val newContent = liveChange?.newContent ?: decoded.orEmpty()
    val oldContent = liveChange?.oldContent
    val showDiff = liveChange != null
    val clipboard = LocalClipboardManager.current
    var copiedPath by remember { mutableStateOf(false) }
    LaunchedEffect(copiedPath) {
        if (copiedPath) {
            delay(1_500)
            copiedPath = false
        }
    }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp))
            .bubbleIn(key = "write-${message.id}"),
    ) {
        Column {
            // Header: what is being changed + status
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.EditNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.chat_proposed_change),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = message.filePath.orEmpty(),
                    style = CodeTextStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = {
                        val body = if (showDiff) newContent else decoded.orEmpty()
                        if (body.isNotEmpty()) {
                            clipboard.setText(AnnotatedString(body))
                            copiedPath = true
                        }
                    },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = if (copiedPath) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                        contentDescription = stringResource(R.string.chat_code_copy),
                        tint = if (copiedPath) {
                            MaterialTheme.colorScheme.secondary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(18.dp),
                    )
                }
                StatusChip(message = message, branch = branch)
            }

            if (showDiff) {
                Text(
                    text = stringResource(
                        R.string.chat_additions_removals,
                        liveChange?.additions ?: 0,
                        liveChange?.removals ?: 0,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
                )
                Text(
                    text = if (liveChange?.isNew == true) {
                        stringResource(R.string.chat_new_file)
                    } else {
                        message.commitMessage.orEmpty()
                    },
                    style = CodeTextStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
                )
                DiffView(
                    oldText = oldContent.orEmpty(),
                    newText = newContent,
                    language = guessLanguage(message.filePath),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            } else if (newContent.isNotBlank()) {
                // History without live diff — still show content in a CodeBlock.
                CodeBlock(
                    code = newContent,
                    language = guessLanguage(message.filePath),
                    collapsible = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
                if (message.commitMessage.orEmpty().isNotBlank()) {
                    Text(
                        text = message.commitMessage.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                    )
                }
            } else {
                Text(
                    text = message.commitMessage.orEmpty().ifEmpty {
                        stringResource(R.string.chat_proposed_change)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                )
            }

            if (committing) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.chat_committing),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (gateActive) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(onClick = onReject) {
                        Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.chat_reject))
                    }
                    Button(
                        onClick = onApprove,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary,
                            contentColor = MaterialTheme.colorScheme.onSecondary,
                        ),
                    ) {
                        Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.chat_approve))
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusChip(message: ChatMessage, branch: String?) {
    when (message.status) {
        MessageStatus.APPROVED -> InfoChip(
            text = branch?.let { stringResource(R.string.chat_committed_to, it) }
                ?: stringResource(R.string.chat_committing),
            icon = Icons.Rounded.CheckCircle,
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        MessageStatus.REJECTED -> InfoChip(
            text = stringResource(R.string.chat_reject),
            icon = Icons.Rounded.Close,
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        )
        MessageStatus.PENDING -> InfoChip(
            text = stringResource(R.string.chat_pending),
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.8f),
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        )
        MessageStatus.NONE -> {}
    }
}

@Composable
fun TypingBubble(step: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 6.dp, bottomEnd = 20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                dev.repochat.ui.components.TypingDots()
                if (step.isNotBlank()) {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = step,
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

internal fun guessLanguage(path: String?): String? {
    val name = path?.substringAfterLast('/') ?: return null
    val ext = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return when (ext) {
        "kt", "kts" -> "kotlin"
        "java" -> "java"
        "js", "mjs", "cjs" -> "javascript"
        "ts", "tsx" -> "typescript"
        "py" -> "python"
        "xml" -> "xml"
        "gradle" -> "groovy"
        "md" -> "markdown"
        "json" -> "json"
        "yml", "yaml" -> "yaml"
        "sh", "bash" -> "bash"
        "sql" -> "sql"
        "css" -> "css"
        "html", "htm" -> "html"
        "swift" -> "swift"
        "go" -> "go"
        "rs" -> "rust"
        "c", "h" -> "c"
        "cpp", "cc", "hpp" -> "cpp"
        else -> ext.ifBlank { null }
    }
}
