package dev.repochat.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
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

/** Native dashboard backed by saved conversations, not mock activity. */
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
    var query by rememberSaveable { mutableStateOf("") }
    var showNewChoices by rememberSaveable { mutableStateOf(false) }
    val filtered = remember(conversations, query) {
        val q = query.trim()
        conversations.filter {
            q.isEmpty() || it.session.displayTitle.contains(q, ignoreCase = true) ||
                "${it.session.owner}/${it.session.repo}".contains(q, ignoreCase = true) ||
                it.lastMessagePreview?.contains(q, ignoreCase = true) == true
        }
    }
    val recentRepo = conversations.firstOrNull { !it.session.isGeneral }?.session
    val openConversation: (ConversationSummary) -> Unit = { row ->
        val s = row.session
        if (s.isGeneral) onOpenChat("", "", "", "GENERAL", s.repoKey)
        else onOpenChat(s.owner, s.repo, s.defaultBranch, "REPO", s.repoKey)
    }

    if (showNewChoices) {
        AlertDialog(
            onDismissRequest = { showNewChoices = false },
            title = { Text(stringResource(R.string.chats_new_title)) },
            text = {
                Column {
                    TextButton(onClick = {
                        showNewChoices = false
                        onOpenGeneralChat()
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.chats_new_general))
                    }
                    TextButton(onClick = {
                        showNewChoices = false
                        onOpenRepos()
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.chats_new_repo))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showNewChoices = false }) {
                    Text(stringResource(R.string.chat_cancel))
                }
            },
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        modifier = modifier,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.width(320.dp)) {
                Column(Modifier.fillMaxHeight().padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.chats_title),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f))
                        IconButton(onClick = {
                            scope.launch { drawerState.close() }
                            showNewChoices = true
                        }) {
                            Icon(Icons.Rounded.Add, stringResource(R.string.chats_new))
                        }
                    }
                    ConversationSearch(query = query, onQueryChange = { query = it })
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        if (filtered.isEmpty()) {
                            item { EmptyConversations(query) }
                        }
                        items(filtered, key = { it.session.repoKey }) { row ->
                            ConversationRow(row) {
                                scope.launch { drawerState.close() }
                                openConversation(row)
                            }
                        }
                    }
                    HorizontalDivider()
                    TextButton(onClick = {
                        scope.launch { drawerState.close() }
                        onOpenRepos()
                    }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.Folder, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.repos_title))
                    }
                    TextButton(onClick = {
                        scope.launch { drawerState.close() }
                        onOpenSettings()
                    }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.Settings, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.tab_settings))
                    }
                }
            }
        },
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                Column {
                    TopAppBar(
                        title = { Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge) },
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Rounded.Menu, stringResource(R.string.home_open_drawer))
                            }
                        },
                        actions = {
                            IconButton(onClick = { showNewChoices = true }) {
                                Icon(Icons.Rounded.Add, stringResource(R.string.chats_new))
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            },
            bottomBar = {
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                        NavigationBarItem(selected = true, onClick = {},
                            icon = { Icon(Icons.Rounded.Home, null) },
                            label = { Text(stringResource(R.string.workspace_home)) })
                        NavigationBarItem(selected = false,
                            onClick = { scope.launch { drawerState.open() } },
                            icon = { Icon(Icons.Rounded.ChatBubbleOutline, null) },
                            label = { Text(stringResource(R.string.chats_title)) })
                        NavigationBarItem(selected = false, onClick = onOpenRepos,
                            icon = { Icon(Icons.Rounded.Folder, null) },
                            label = { Text(stringResource(R.string.repos_title)) })
                        NavigationBarItem(selected = false, onClick = onOpenSettings,
                            icon = { Icon(Icons.Rounded.Settings, null) },
                            label = { Text(stringResource(R.string.tab_settings)) })
                    }
                }
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.workspace_eyebrow),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stringResource(R.string.workspace_title), style = MaterialTheme.typography.headlineMedium)
                        Text(stringResource(R.string.workspace_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                item { ConversationSearch(query = query, onQueryChange = { query = it }) }
                item {
                    Text(stringResource(R.string.workspace_recent_repo),
                        modifier = Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.titleSmall)
                }
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Icon(Icons.Rounded.Folder, null, tint = MaterialTheme.colorScheme.primary)
                                Text(
                                    text = recentRepo?.let { "${it.owner} / ${it.repo}" }
                                        ?: stringResource(R.string.workspace_choose_repo),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(stringResource(R.string.workspace_repo_description),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Button(onClick = {
                                val s = recentRepo
                                if (s == null) onOpenRepos()
                                else onOpenChat(s.owner, s.repo, s.defaultBranch, "REPO", s.repoKey)
                            }, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Rounded.SmartToy, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.workspace_start_coding))
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        TextButton(onClick = {
                            val s = recentRepo
                            if (s == null) onOpenRepos()
                            else onOpenRepoDetail(s.owner, s.repo, s.defaultBranch)
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.workspace_browse_files))
                        }
                    }
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.workspace_activity),
                            style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { scope.launch { drawerState.open() } }) {
                            Text(stringResource(R.string.workspace_view_all))
                        }
                    }
                }
                if (filtered.isEmpty()) {
                    item { EmptyConversations(query) }
                }
                items(filtered.take(5), key = { it.session.repoKey }) { row ->
                    ConversationRow(row) { openConversation(row) }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.workspace_branch_hint),
                            modifier = Modifier.padding(14.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationSearch(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text(stringResource(R.string.chats_search)) },
        leadingIcon = { Icon(Icons.Rounded.Search, null) },
        singleLine = true,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun EmptyConversations(query: String) {
    Text(stringResource(if (query.isBlank()) R.string.workspace_no_chats else R.string.workspace_no_matches),
        modifier = Modifier.padding(vertical = 16.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ConversationRow(summary: ConversationSummary, onClick: () -> Unit) {
    val s = summary.session
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp, horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (s.isGeneral) Icons.Rounded.ChatBubbleOutline else Icons.Rounded.SmartToy,
            contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(s.displayTitle, style = MaterialTheme.typography.titleSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            val subtitle = if (s.isGeneral) summary.lastMessagePreview.orEmpty() else "${s.owner}/${s.repo}"
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(timeAgo(summary.lastMessageAt.takeIf { it > 0 } ?: s.updatedAt),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
