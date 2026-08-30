package dev.repochat.ui.repos

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.repochat.R
import dev.repochat.core.model.TreeEntry
import dev.repochat.ui.chat.markdown.CodeBlock
import dev.repochat.ui.components.EmptyState
import dev.repochat.ui.components.InfoChip
import dev.repochat.ui.components.ShimmerBox

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoDetailScreen(
    owner: String,
    repo: String,
    defaultBranch: String,
    onBack: () -> Unit,
    onChatAboutRepo: () -> Unit,
    viewModel: RepoDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(owner, repo, defaultBranch) {
        viewModel.start(owner, repo, defaultBranch)
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val openPath = state.openPath

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "$owner/$repo",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = defaultBranch,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
                    IconButton(onClick = viewModel::refresh) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = stringResource(R.string.repos_retry),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text(stringResource(R.string.repo_chat_about)) },
                icon = { Icon(Icons.Rounded.ChatBubble, contentDescription = null) },
                onClick = { viewModel.prepareChat(onChatAboutRepo) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
    ) { padding ->
        when {
            state.loading -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    repeat(12) {
                        ShimmerBox(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(36.dp),
                        )
                    }
                }
            }

            state.error != null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    EmptyState(
                        icon = Icons.Rounded.ErrorOutline,
                        title = stringResource(R.string.repo_tree_error_title),
                        body = state.error?.userMessage.orEmpty(),
                        action = {
                            Button(onClick = viewModel::refresh) {
                                Text(stringResource(R.string.repos_retry))
                            }
                        },
                    )
                }
            }

            state.entries.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    EmptyState(
                        icon = Icons.Rounded.Folder,
                        title = stringResource(R.string.repo_tree_empty_title),
                        body = stringResource(R.string.repo_tree_empty_body),
                    )
                }
            }

            else -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                ) {
                    if (state.truncated) {
                        InfoChip(
                            text = stringResource(R.string.chat_tree_truncated),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                    }
                    LazyColumn(
                        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 96.dp),
                    ) {
                        items(state.entries, key = { it.path }) { entry ->
                            TreeRow(
                                entry = entry,
                                onClick = {
                                    if (entry.type == "blob" || entry.type == "file") {
                                        viewModel.openFile(entry.path)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (openPath != null) {
        ModalBottomSheet(
            onDismissRequest = viewModel::closeFile,
            sheetState = sheetState,
        ) {
            FileViewerSheet(
                path = openPath,
                loading = state.openLoading,
                content = state.openContent,
                binary = state.openBinary,
                error = state.openError,
                onClose = viewModel::closeFile,
            )
        }
    }
}

@Composable
private fun TreeRow(entry: TreeEntry, onClick: () -> Unit) {
    val isDir = entry.type == "tree" || entry.type == "dir"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isDir, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (isDir) Icons.Rounded.Folder else Icons.Rounded.InsertDriveFile,
            contentDescription = null,
            tint = if (isDir) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = entry.path,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun FileViewerSheet(
    path: String,
    loading: Boolean,
    content: String?,
    binary: Boolean,
    error: String?,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 28.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Rounded.Description,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = path,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClose) {
                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.chat_cancel))
            }
        }
        Spacer(Modifier.height(12.dp))
        when {
            loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                }
            }
            error != null -> {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            binary -> {
                Text(
                    text = stringResource(R.string.repo_file_binary),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            content != null -> {
                val ext = path.substringAfterLast('.', missingDelimiterValue = "")
                    .lowercase()
                    .ifBlank { null }
                // Cap huge files in the viewer so Compose stays snappy.
                val shown = if (content.length > 80_000) {
                    content.take(80_000) + "\n\n… (truncated)"
                } else {
                    content
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    CodeBlock(code = shown, language = ext)
                }
            }
        }
    }
}
