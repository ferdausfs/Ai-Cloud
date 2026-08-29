package dev.repochat.ui.chat

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.rememberSharedContentState
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CallMerge
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.repochat.R
import dev.repochat.core.model.AppError
import dev.repochat.core.model.MessageStatus
import dev.repochat.core.model.PullRequestInfo
import dev.repochat.ui.components.EmptyState
import dev.repochat.ui.components.InfoChip
import dev.repochat.ui.components.bounce
import dev.repochat.ui.theme.Teal400
import kotlinx.coroutines.launch

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalSharedTransitionApi::class,
    ExperimentalLayoutApi::class,
)
@Composable
fun ChatScreen(
    owner: String,
    repo: String,
    defaultBranch: String,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var input by rememberSaveable { mutableStateOf("") }
    var showBranchInfo by rememberSaveable { mutableStateOf(false) }
    var showClearDialog by rememberSaveable { mutableStateOf(false) }
    var showMenu by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(owner, repo, defaultBranch) {
        viewModel.start(owner, repo, defaultBranch)
    }

    LaunchedEffect(state.snackbar.id) {
        val event = state.snackbar
        if (event.id != 0L) {
            snackbarHostState.showSnackbar(
                stringResource(event.textRes, *event.args.toTypedArray())
            )
            viewModel.onSnackbarShown()
        }
    }

    LaunchedEffect(state.treeTruncated) {
        if (state.treeTruncated) {
            snackbarHostState.showSnackbar(stringResource(R.string.chat_tree_truncated))
            viewModel.consumeTreeTruncated()
        }
    }

    val itemCount = state.messages.size + if (state.typing) 1 else 0
    LaunchedEffect(itemCount) {
        if (itemCount > 0) listState.animateScrollToItem(itemCount - 1)
    }

    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    val session = state.session
    val canSend = session != null && !state.typing && !state.approvalPending && !state.approving
    val workingBranch = session?.workingBranch ?: session?.let { "ai-chat/${it.sessionId}" }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        with(sharedTransitionScope) {
                            Text(
                                text = "$owner/$repo",
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f, fill = false)
                                    .sharedElement(
                                        state = rememberSharedContentState(key = "repo-$owner/$repo"),
                                        animatedVisibilityScope = animatedVisibilityScope,
                                    ),
                            )
                        }
                        if (workingBranch != null) {
                            Spacer(Modifier.width(8.dp))
                            InfoChip(
                                text = workingBranch,
                                icon = Icons.Rounded.AccountTree,
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.bounce { showBranchInfo = true },
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.chat_back),
                        )
                    }
                },
                actions = {
                    if (session != null && session.workingBranch != null) {
                        IconButton(
                            onClick = viewModel::createPullRequestNow,
                            enabled = state.prState != PrState.Creating,
                        ) {
                            if (state.prState == PrState.Creating) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.CallMerge,
                                    contentDescription = stringResource(R.string.chat_pr),
                                    tint = MaterialTheme.colorScheme.secondary,
                                )
                            }
                        }
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(
                                imageVector = Icons.Rounded.MoreVert,
                                contentDescription = stringResource(R.string.chat_more),
                            )
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_clear)) },
                                leadingIcon = { Icon(Icons.Rounded.DeleteSweep, contentDescription = null) },
                                onClick = {
                                    showMenu = false
                                    showClearDialog = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_settings)) },
                                leadingIcon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
                                onClick = {
                                    showMenu = false
                                    onOpenSettings()
                                },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // Error banner with retry / settings shortcut.
            AnimatedVisibility(
                visible = state.error != null,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                state.error?.let { error ->
                    ErrorBanner(
                        error = error,
                        canRetry = state.canRetry,
                        onRetry = viewModel::retry,
                        onOpenSettings = onOpenSettings,
                        onDismiss = viewModel::dismissError,
                    )
                }
            }

            if (state.messages.isEmpty() && !state.typing) {
                EmptyChat(
                    onSuggestion = viewModel::send,
                    modifier = Modifier.weight(1f),
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.messages, key = { it.id }) { message ->
                        MessageItem(
                            message = message,
                            liveChange = state.liveChange?.takeIf {
                                it.path == message.filePath && message.status == MessageStatus.PENDING
                            },
                            gateActive = state.approvalPending &&
                                message.id == state.pendingWriteMessageId &&
                                message.status == MessageStatus.PENDING,
                            committing = state.approving && message.id == state.pendingWriteMessageId,
                            branch = workingBranch,
                            onApprove = viewModel::approveChange,
                            onReject = viewModel::rejectChange,
                            modifier = Modifier.animateItem(),
                        )
                    }
                    if (state.typing) {
                        item(key = "typing") {
                            TypingBubble(step = state.workingStep)
                        }
                    }
                }
            }

            BottomBar(
                approvalPending = state.approvalPending,
                approving = state.approving,
                input = input,
                onInputChange = { input = it },
                canSend = canSend,
                onSend = {
                    viewModel.send(input)
                    input = ""
                },
                onApprove = viewModel::approveChange,
                onReject = viewModel::rejectChange,
            )
        }
    }

    // ---------- dialogs ----------

    if (showBranchInfo && session != null) {
        AlertDialog(
            onDismissRequest = { showBranchInfo = false },
            title = { Text(stringResource(R.string.chat_branch_info_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.chat_branch_info_body,
                        workingBranch ?: "",
                        session.defaultBranch,
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = { showBranchInfo = false }) {
                    Text(stringResource(R.string.chat_branch_info_got_it))
                }
            },
        )
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(R.string.chat_clear_confirm_title)) },
            text = { Text(stringResource(R.string.chat_clear_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearDialog = false
                        viewModel.clearConversation()
                    },
                ) {
                    Text(
                        stringResource(R.string.chat_clear_confirm_yes),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text(stringResource(R.string.chat_cancel))
                }
            },
        )
    }

    when (val pr = state.prState) {
        is PrState.Creating -> PrCreatingDialog()
        is PrState.Ready -> PrReadyDialog(
            info = pr.info,
            onOpen = {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(pr.info.htmlUrl)))
                } catch (_: Exception) {
                    // No browser available — the copy action still works.
                }
            },
            onCopy = {
                clipboardManager.setText(AnnotatedString(pr.info.htmlUrl))
                scope.launch { snackbarHostState.showSnackbar(stringResource(R.string.chat_copied)) }
            },
            onDismiss = viewModel::dismissPrDialog,
        )
        is PrState.Failed -> AlertDialog(
            onDismissRequest = viewModel::dismissPrDialog,
            title = { Text(stringResource(R.string.chat_pr_failed_title)) },
            text = { Text(pr.message) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissPrDialog) {
                    Text(stringResource(R.string.chat_pr_dialog_done))
                }
            },
        )
        PrState.None -> Unit
    }
}

@Composable
private fun EmptyChat(onSuggestion: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        EmptyState(
            icon = Icons.Rounded.SmartToy,
            title = stringResource(R.string.chat_empty_title),
            body = stringResource(R.string.chat_empty_body),
            modifier = Modifier.fillMaxWidth(),
        )
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SuggestionChip(
                icon = Icons.Rounded.Bolt,
                label = stringResource(R.string.chat_suggest_fix),
                onClick = { onSuggestion(stringResource(R.string.chat_suggest_fix)) },
            )
            SuggestionChip(
                icon = Icons.Rounded.SmartToy,
                label = stringResource(R.string.chat_suggest_explain),
                onClick = { onSuggestion(stringResource(R.string.chat_suggest_explain)) },
            )
            SuggestionChip(
                icon = Icons.Rounded.Check,
                label = stringResource(R.string.chat_suggest_test),
                onClick = { onSuggestion(stringResource(R.string.chat_suggest_test)) },
            )
            SuggestionChip(
                icon = Icons.Rounded.Settings,
                label = stringResource(R.string.chat_suggest_readme),
                onClick = { onSuggestion(stringResource(R.string.chat_suggest_readme)) },
            )
        }
    }
}

@Composable
private fun SuggestionChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(text = label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun ErrorBanner(
    error: AppError,
    canRetry: Boolean,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.ErrorOutline, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                text = error.userMessage,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            if (canRetry) {
                TextButton(onClick = onRetry) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.chat_retry))
                }
            }
            if (error is AppError.Unauthorized && error.provider == AppError.Provider.GITHUB) {
                TextButton(onClick = onOpenSettings) {
                    Text(stringResource(R.string.chat_error_unauthorized_action))
                }
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun BottomBar(
    approvalPending: Boolean,
    approving: Boolean,
    input: String,
    onInputChange: (String) -> Unit,
    canSend: Boolean,
    onSend: () -> Unit,
    onApprove: () -> Unit,
    onReject: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            if (approvalPending || approving) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = onReject,
                        enabled = !approving,
                        modifier = Modifier
                            .weight(0.4f)
                            .bounce { if (!approving) onReject() },
                    ) {
                        Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.chat_reject))
                    }
                    Button(
                        onClick = onApprove,
                        enabled = !approving,
                        modifier = Modifier
                            .weight(0.6f)
                            .bounce { if (!approving) onApprove() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary,
                            contentColor = MaterialTheme.colorScheme.onSecondary,
                        ),
                    ) {
                        if (approving) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (approving) stringResource(R.string.chat_committing)
                            else stringResource(R.string.chat_approve)
                        )
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = onInputChange,
                        placeholder = { Text(stringResource(R.string.chat_input_hint)) },
                        modifier = Modifier.weight(1f),
                        shape = MaterialTheme.shapes.extraLarge,
                        maxLines = 4,
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(
                                color = if (canSend && input.isNotBlank()) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                                shape = CircleShape,
                            )
                            .bounce { if (canSend && input.isNotBlank()) onSend() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.Send,
                            contentDescription = stringResource(R.string.chat_send),
                            tint = if (canSend && input.isNotBlank()) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PrCreatingDialog() {
    Dialog(onDismissRequest = {}) {
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
            Row(
                modifier = Modifier.padding(24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(14.dp))
                Text(
                    text = stringResource(R.string.chat_pr_creating),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

@Composable
private fun PrReadyDialog(
    info: PullRequestInfo,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.padding(22.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(Teal400.copy(alpha = 0.18f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CallMerge,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.chat_pr_dialog_title),
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(
                        R.string.chat_pr_dialog_body,
                        info.number,
                        info.title,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(18.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onCopy) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.chat_pr_dialog_copy))
                    }
                    Spacer(Modifier.width(6.dp))
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.chat_pr_dialog_done))
                    }
                    Spacer(Modifier.width(6.dp))
                    Button(onClick = onOpen) {
                        Icon(Icons.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.chat_pr_dialog_open))
                    }
                }
            }
        }
    }
}
