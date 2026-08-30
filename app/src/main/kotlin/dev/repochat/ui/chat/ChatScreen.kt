package dev.repochat.ui.chat

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CallMerge
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Sync
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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
import dev.repochat.core.model.ChatAttachment
import dev.repochat.core.model.MessageStatus
import dev.repochat.core.model.PullRequestInfo
import dev.repochat.ui.components.EmptyState
import dev.repochat.ui.components.InfoChip
import dev.repochat.ui.components.bounce
import dev.repochat.ui.theme.Teal400
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalSharedTransitionApi::class,
    ExperimentalLayoutApi::class,
    ExperimentalFoundationApi::class,
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

    // Resolved during composition so the effects/lambdas below never call
    // @Composable stringResource() from a non-composable context.
    val snackbarText = state.snackbar
        .takeIf { it.id != 0L }
        ?.let { stringResource(it.textRes, *it.args.toTypedArray()) }
    val treeTruncatedText = stringResource(R.string.chat_tree_truncated)
    val copiedMessage = stringResource(R.string.chat_copied)

    LaunchedEffect(state.snackbar.id, snackbarText) {
        if (snackbarText != null) {
            snackbarHostState.showSnackbar(snackbarText)
            viewModel.onSnackbarShown()
        }
    }

    LaunchedEffect(state.treeTruncated) {
        if (state.treeTruncated) {
            snackbarHostState.showSnackbar(treeTruncatedText)
            viewModel.consumeTreeTruncated()
        }
    }

    val itemCount = state.messages.size + if (state.typing) 1 else 0
    LaunchedEffect(itemCount) {
        if (itemCount > 0) listState.animateScrollToItem(itemCount - 1)
    }

    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val attachFailedText = stringResource(R.string.chat_attach_failed)
    val attachTooLargeText = stringResource(R.string.chat_attach_too_large)

    // OpenDocument accepts images + text/code so users can attach screenshots
    // or source files without switching pickers. "*/*" is intentionally broad
    // — content is classified by MIME / extension after the pick.
    val pickFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            // Persist read access across process death for the pending chip.
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
            // Some providers don't support persistable grants — still readable now.
        }
        val meta = readAttachmentMeta(context, uri)
        viewModel.setPendingAttachment(meta)
    }

    val session = state.session
    val hasAttachment = state.pendingAttachment != null
    val canSend = session != null &&
        !state.typing &&
        !state.approvalPending &&
        !state.approving &&
        (input.isNotBlank() || hasAttachment)
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
                        state.ciStatus?.let { ci ->
                            Spacer(Modifier.width(6.dp))
                            val (icon, container, content) = ciChipColors(ci.conclusion, ci.status)
                            InfoChip(
                                text = ci.chipLabel(),
                                icon = icon,
                                containerColor = container,
                                contentColor = content,
                                modifier = Modifier.bounce {
                                    ci.htmlUrl?.let { url ->
                                        try {
                                            context.startActivity(
                                                Intent(Intent.ACTION_VIEW, Uri.parse(url)),
                                            )
                                        } catch (_: Exception) {
                                            // No browser — ignore.
                                        }
                                    }
                                },
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
                    onSuggestion = { viewModel.send(it) },
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
                pendingAttachment = state.pendingAttachment,
                onAttach = {
                    pickFile.launch(
                        arrayOf(
                            "image/*",
                            "text/*",
                            "application/json",
                            "application/xml",
                            "application/javascript",
                            "application/typescript",
                            "application/x-yaml",
                            "application/yaml",
                            "application/toml",
                            "application/*",
                        ),
                    )
                },
                onRemoveAttachment = viewModel::clearPendingAttachment,
                onSend = {
                    val pending = state.pendingAttachment
                    val typed = input
                    input = ""
                    if (pending == null) {
                        viewModel.send(typed)
                    } else {
                        scope.launch {
                            val loaded = withContext(Dispatchers.IO) {
                                loadAttachment(context, pending)
                            }
                            when (loaded) {
                                is AttachmentLoad.Ok -> viewModel.send(typed, loaded.attachment)
                                is AttachmentLoad.TooLarge -> {
                                    snackbarHostState.showSnackbar(attachTooLargeText)
                                    viewModel.clearPendingAttachment()
                                }
                                is AttachmentLoad.Failed -> {
                                    snackbarHostState.showSnackbar(attachFailedText)
                                    viewModel.clearPendingAttachment()
                                }
                            }
                        }
                    }
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
                scope.launch { snackbarHostState.showSnackbar(copiedMessage) }
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptyChat(onSuggestion: (String) -> Unit, modifier: Modifier = Modifier) {
    val fixLabel = stringResource(R.string.chat_suggest_fix)
    val explainLabel = stringResource(R.string.chat_suggest_explain)
    val testLabel = stringResource(R.string.chat_suggest_test)
    val readmeLabel = stringResource(R.string.chat_suggest_readme)
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
                label = fixLabel,
                onClick = { onSuggestion(fixLabel) },
            )
            SuggestionChip(
                icon = Icons.Rounded.SmartToy,
                label = explainLabel,
                onClick = { onSuggestion(explainLabel) },
            )
            SuggestionChip(
                icon = Icons.Rounded.Check,
                label = testLabel,
                onClick = { onSuggestion(testLabel) },
            )
            SuggestionChip(
                icon = Icons.Rounded.Settings,
                label = readmeLabel,
                onClick = { onSuggestion(readmeLabel) },
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
private fun ciChipColors(
    conclusion: String?,
    status: String,
): Triple<
    androidx.compose.ui.graphics.vector.ImageVector,
    androidx.compose.ui.graphics.Color,
    androidx.compose.ui.graphics.Color,
    > {
    val scheme = MaterialTheme.colorScheme
    return when {
        status == "queued" -> Triple(
            Icons.Rounded.HourglassEmpty,
            scheme.surfaceVariant,
            scheme.onSurfaceVariant,
        )
        status == "in_progress" -> Triple(
            Icons.Rounded.Sync,
            scheme.tertiaryContainer,
            scheme.onTertiaryContainer,
        )
        conclusion == "success" -> Triple(
            Icons.Rounded.CheckCircle,
            scheme.secondaryContainer,
            scheme.onSecondaryContainer,
        )
        conclusion == "failure" -> Triple(
            Icons.Rounded.ErrorOutline,
            scheme.errorContainer,
            scheme.onErrorContainer,
        )
        else -> Triple(
            Icons.Rounded.PlayCircle,
            scheme.surfaceVariant,
            scheme.onSurfaceVariant,
        )
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
    pendingAttachment: PendingAttachment?,
    onAttach: () -> Unit,
    onRemoveAttachment: () -> Unit,
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
                AnimatedVisibility(
                    visible = pendingAttachment != null,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    pendingAttachment?.let { attachment ->
                        AttachmentChip(
                            attachment = attachment,
                            onRemove = onRemoveAttachment,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onAttach,
                        modifier = Modifier.size(44.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AttachFile,
                            contentDescription = stringResource(R.string.chat_attach),
                            tint = if (pendingAttachment != null) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
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
                                color = if (canSend) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                                shape = CircleShape,
                            )
                            .bounce { if (canSend) onSend() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.Send,
                            contentDescription = stringResource(R.string.chat_send),
                            tint = if (canSend) {
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
private fun AttachmentChip(
    attachment: PendingAttachment,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val thumb: androidx.compose.ui.graphics.ImageBitmap? = remember(attachment.uriString, attachment.isImage) {
        if (!attachment.isImage) {
            null
        } else {
            try {
                context.contentResolver.openInputStream(Uri.parse(attachment.uriString))?.use { stream ->
                    val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                    BitmapFactory.decodeStream(stream, null, opts)?.asImageBitmap()
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier
                .padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)
                .widthIn(max = 280.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val imageBitmap = thumb
            if (imageBitmap != null) {
                Image(
                    bitmap = imageBitmap,
                    contentDescription = null,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            RoundedCornerShape(8.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (attachment.isImage) {
                            Icons.Rounded.Description
                        } else {
                            Icons.Rounded.InsertDriveFile
                        },
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = attachment.displayName,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.chat_attach_remove),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

private sealed interface AttachmentLoad {
    data class Ok(val attachment: ChatAttachment) : AttachmentLoad
    data object TooLarge : AttachmentLoad
    data object Failed : AttachmentLoad
}

private const val MAX_ATTACHMENT_BYTES = 5L * 1024L * 1024L

private val TEXT_EXTENSIONS = setOf(
    "txt", "md", "markdown", "json", "xml", "yml", "yaml", "toml", "csv",
    "kt", "kts", "java", "js", "jsx", "ts", "tsx", "py", "rb", "go", "rs",
    "c", "h", "cpp", "hpp", "cs", "swift", "m", "mm", "php", "sql", "sh",
    "bash", "zsh", "ps1", "gradle", "properties", "ini", "cfg", "conf",
    "html", "htm", "css", "scss", "sass", "less", "svg", "r", "pl", "lua",
    "dart", "scala", "groovy", "cmake", "makefile", "dockerfile", "gitignore",
    "env", "log", "diff", "patch",
)

private fun readAttachmentMeta(context: android.content.Context, uri: Uri): PendingAttachment {
    var name = "attachment"
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (cursor.moveToFirst() && nameIdx >= 0) {
            name = cursor.getString(nameIdx) ?: name
        }
    }
    val mime = context.contentResolver.getType(uri)
    val isImage = mime?.startsWith("image/") == true ||
        name.substringAfterLast('.', "").lowercase() in setOf(
            "png", "jpg", "jpeg", "gif", "webp", "bmp", "heic",
        )
    return PendingAttachment(
        uriString = uri.toString(),
        displayName = name,
        mimeType = mime,
        isImage = isImage,
    )
}

private fun loadAttachment(
    context: android.content.Context,
    pending: PendingAttachment,
): AttachmentLoad {
    val uri = Uri.parse(pending.uriString)
    return try {
        // Guard on declared size when available.
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst() && sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                if (cursor.getLong(sizeIdx) > MAX_ATTACHMENT_BYTES) return AttachmentLoad.TooLarge
            }
        }

        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return AttachmentLoad.Failed
        if (bytes.size.toLong() > MAX_ATTACHMENT_BYTES) return AttachmentLoad.TooLarge

        if (pending.isImage) {
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            AttachmentLoad.Ok(
                ChatAttachment(
                    displayName = pending.displayName,
                    mimeType = pending.mimeType ?: "image/*",
                    imageBase64 = b64,
                ),
            )
        } else if (looksLikeText(pending.displayName, pending.mimeType, bytes)) {
            val text = bytes.toString(Charsets.UTF_8)
            AttachmentLoad.Ok(
                ChatAttachment(
                    displayName = pending.displayName,
                    mimeType = pending.mimeType ?: "text/plain",
                    textContent = text,
                ),
            )
        } else {
            // Unknown binary — surface a short note rather than dumping garbage.
            AttachmentLoad.Ok(
                ChatAttachment(
                    displayName = pending.displayName,
                    mimeType = pending.mimeType,
                    textContent = "(binary file; ${bytes.size} bytes — contents not included)",
                ),
            )
        }
    } catch (_: Exception) {
        AttachmentLoad.Failed
    }
}

private fun looksLikeText(name: String, mime: String?, bytes: ByteArray): Boolean {
    if (mime != null) {
        if (mime.startsWith("text/")) return true
        if (mime in setOf(
                "application/json",
                "application/xml",
                "application/javascript",
                "application/typescript",
                "application/x-yaml",
                "application/yaml",
                "application/toml",
                "application/x-sh",
                "application/sql",
            )
        ) return true
    }
    val ext = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    if (ext in TEXT_EXTENSIONS) return true
    // Heuristic: mostly printable / whitespace and no NULs in the first 2 KB.
    val sample = bytes.take(2_048)
    if (sample.any { it == 0.toByte() }) return false
    val nonText = sample.count { b ->
        val c = b.toInt() and 0xFF
        c < 0x09 || (c in 0x0E..0x1F)
    }
    return nonText < sample.size / 10
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
