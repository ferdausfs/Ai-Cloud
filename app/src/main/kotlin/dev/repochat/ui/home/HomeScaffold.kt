package dev.repochat.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.repochat.R
import dev.repochat.core.model.ConversationSummary
import dev.repochat.ui.chats.ChatsHomeViewModel
import dev.repochat.ui.components.timeAgo
import kotlinx.coroutines.launch

/**
 * HuggingChat-style shell: drawer for history + destinations, empty home
 * until a chat is opened (chats open full-screen via [onOpenChat]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScaffold(
    onOpenChat: (owner: String, repo: String, defaultBranch: String, mode: String, repoKey: String) -> Unit,
    onOpenGeneralChat: () -> Unit,
    onOpenRepoDetail: (owner: String, repo: String, defaultBranch: String) -> Unit,
    onOpenRepos: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    chatsViewModel: ChatsHomeViewModel = hiltViewModel(),
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val conversations by chatsViewModel.conversations.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var showNewChoices by remember { mutableStateOf(false) }

    val filtered = remember(conversations, query) {
        val q = query.trim()
        if (q.isEmpty()) conversations
        else conversations.filter {
            it.session.displayTitle.contains(q, ignoreCase = true) ||
                it.lastMessagePreview?.contains(q, ignoreCase = true) == true
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        modifier = modifier,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.width(320.dp)) {
                Column(
                    Modifier
                        .fillMaxHeight()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(R.string.chats_title),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = {
                                showNewChoices = true
                                scope.launch { drawerState.close() }
                            },
                        ) {
                            Icon(
                                Icons.Rounded.Add,
                                contentDescription = stringResource(R.string.chats_new),
                            )
                        }
                    }
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text(stringResource(R.string.chats_search)) },
                        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(bottom = 12.dp),
                    ) {
                        items(filtered, key = { it.session.repoKey }) { row ->
                            DrawerChatRow(
                                summary = row,
                                onClick = {
                                    scope.launch { drawerState.close() }
                                    val s = row.session
                                    if (s.isGeneral) {
                                        onOpenChat("", "", "", "GENERAL", s.repoKey)
                                    } else {
                                        onOpenChat(
                                            s.owner, s.repo, s.defaultBranch, "REPO", s.repoKey,
                                        )
                                    }
                                },
                            )
                        }
                    }
                    HorizontalDivider()
                    TextButton(
                        onClick = {
                            scope.launch { drawerState.close() }
                            onOpenRepos()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.Folder, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.repos_title))
                    }
                    TextButton(
                        onClick = {
                            scope.launch { drawerState.close() }
                            onOpenSettings()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.Settings, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.tab_settings))
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        },
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.app_name)) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(
                                Icons.Rounded.Menu,
                                contentDescription = stringResource(R.string.home_open_drawer),
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                )
            },
            floatingActionButton = {
                FloatingActionButton(
                    onClick = { showNewChoices = true },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.chats_new))
                }
            },
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                if (showNewChoices) {
                    NewChatPanel(
                        onGeneral = {
                            showNewChoices = false
                            onOpenGeneralChat()
                        },
                        onRepo = {
                            showNewChoices = false
                            onOpenRepos()
                        },
                        onCancel = { showNewChoices = false },
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp),
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            Icons.Rounded.SmartToy,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.home_empty_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.home_empty_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DrawerChatRow(summary: ConversationSummary, onClick: () -> Unit) {
    val s = summary.session
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = s.displayTitle,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = timeAgo(summary.lastMessageAt.takeIf { it > 0 } ?: s.updatedAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!s.isGeneral) {
            Text(
                text = "${s.owner}/${s.repo}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun NewChatPanel(
    onGeneral: () -> Unit,
    onRepo: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.chats_new_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onGeneral, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.chats_new_general))
        }
        TextButton(onClick = onRepo, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.chats_new_repo))
        }
        TextButton(onClick = onCancel) {
            Text(stringResource(R.string.chat_cancel))
        }
    }
}
