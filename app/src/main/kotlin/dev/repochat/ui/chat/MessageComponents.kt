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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.repochat.R
import dev.repochat.core.model.ChatMessage
import dev.repochat.core.model.ChatRole
import dev.repochat.core.model.MessageKind
import dev.repochat.core.model.MessageStatus
import dev.repochat.core.model.PendingChange
import dev.repochat.ui.components.InfoChip
import dev.repochat.ui.theme.CodeTextStyle

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
            kotlinx.coroutines.launch {
                offsetY.animateTo(0f, tween(280, easing = FastOutSlowInEasing))
            }
            kotlinx.coroutines.launch {
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
    modifier: Modifier = Modifier,
) {
    val shape = if (isUser) {
        RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 6.dp)
    } else {
        RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 6.dp, bottomEnd = 20.dp)
    }
    Surface(
        shape = shape,
        color = if (isUser) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
        contentColor = if (isUser) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        modifier = modifier
            .fillMaxWidth(0.85f)
            .bubbleIn(key = text),
    ) {
        SelectionContainer {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
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
                    .padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 8.dp),
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
                Spacer(Modifier.width(8.dp))
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                        .padding(top = 6.dp),
                )
            } else {
                // Persisted history without live diff context.
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
