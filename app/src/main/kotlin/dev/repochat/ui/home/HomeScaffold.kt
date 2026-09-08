package dev.repochat.ui.home

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.repochat.R
import dev.repochat.core.model.ConversationSummary
import dev.repochat.ui.chats.ChatsHomeScreen
import dev.repochat.ui.chats.ChatsHomeViewModel
import dev.repochat.ui.components.timeAgo
import dev.repochat.ui.repos.RepoDetailScreen
import dev.repochat.ui.settings.SettingsScreen
import dev.repochat.ui.theme.GitHubPurple
import dev.repochat.ui.theme.ThemeViewModel
import dev.repochat.ui.theme.githubCtaButtonColors

/**
 * Ai Cloud main shell — GitHub-inspired 4-tab layout (Home / Workspace /
 * Repository / Settings) with a persistent top bar carrying the brand, the
 * dark/light toggle and the profile avatar. Full-screen destinations
 * (chat, repo picker) open on top of this shell.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScaffold(
    onOpenChat: (owner: String, repo: String, defaultBranch: String, mode: String, repoKey: String) -> Unit,
    onOpenGeneralChat: () -> Unit,
    onOpenRepoDetail: (owner: String, repo: String, defaultBranch: String) -> Unit,
    onOpenRepos: () -> Unit,
    modifier: Modifier = Modifier,
    chatsViewModel: ChatsHomeViewModel = hiltViewModel(),
    themeViewModel: ThemeViewModel = hiltViewModel(),
) {
    val conversations by chatsViewModel.conversations.collectAsStateWithLifecycle()
    val darkOverride by themeViewModel.darkOverride.collectAsStateWithLifecycle()
    val systemDark = isSystemInDarkTheme()
    val isDark = darkOverride ?: systemDark

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(9.dp),
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.size(32.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Rounded.Code,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.background,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { themeViewModel.setDarkTheme(!isDark) },
                    ) {
                        Icon(
                            imageVector = if (isDark) Icons.Rounded.LightMode else Icons.Rounded.DarkMode,
                            contentDescription = stringResource(R.string.theme_toggle),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outline,
                        ),
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .size(32.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "AI",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp,
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Rounded.Home, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_home)) },
                    colors = tabItemColors(),
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Rounded.ChatBubble, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_workspace)) },
                    colors = tabItemColors(),
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Rounded.Code, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_repository)) },
                    colors = tabItemColors(),
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Icon(Icons.Rounded.Tune, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_settings)) },
                    colors = tabItemColors(),
                )
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (selectedTab) {
                0 -> HomeTab(
                    conversations = conversations,
                    onOpenChat = onOpenChat,
                    onOpenGeneralChat = onOpenGeneralChat,
                    onOpenRepoDetail = onOpenRepoDetail,
                    onOpenRepos = onOpenRepos,
                )

                1 -> ChatsHomeScreen(
                    onOpenConversation = { summary ->
                        val s = summary.session
                        if (s.isGeneral) {
                            onOpenChat("", "", "", "GENERAL", s.repoKey)
                        } else {
                            onOpenChat(s.owner, s.repo, s.defaultBranch, "REPO", s.repoKey)
                        }
                    },
                    onNewGeneral = onOpenGeneralChat,
                    onNewRepoChat = onOpenRepos,
                    onBack = null,
                    embedded = true,
                )

                2 -> {
                    val current = conversations
                        .filter { !it.session.isGeneral }
                        .maxByOrNull { maxOf(it.lastMessageAt, it.session.updatedAt) }
                        ?.session
                    if (current != null) {
                        RepoDetailScreen(
                            owner = current.owner,
                            repo = current.repo,
                            defaultBranch = current.defaultBranch,
                            onBack = null,
                            onChatAboutRepo = {
                                onOpenChat(
                                    current.owner,
                                    current.repo,
                                    current.defaultBranch,
                                    "REPO",
                                    current.repoKey,
                                )
                            },
                        )
                    } else {
                        EmptyRepositoryTab(onConnect = onOpenRepos)
                    }
                }

                else -> SettingsScreen(onBack = null, embedded = true)
            }
        }
    }
}

/** Selected = blue icon on raised surface, matching the mockup's nav state. */
@Composable
private fun tabItemColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = MaterialTheme.colorScheme.primary,
    selectedTextColor = MaterialTheme.colorScheme.onBackground,
    indicatorColor = MaterialTheme.colorScheme.surfaceVariant,
    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
)

@Composable
private fun HomeTab(
    conversations: List<ConversationSummary>,
    onOpenChat: (owner: String, repo: String, defaultBranch: String, mode: String, repoKey: String) -> Unit,
    onOpenGeneralChat: () -> Unit,
    onOpenRepoDetail: (owner: String, repo: String, defaultBranch: String) -> Unit,
    onOpenRepos: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val trimmedQuery = query.trim()
    val repoConversations = conversations.filter { !it.session.isGeneral }
    val current = repoConversations
        .maxByOrNull { maxOf(it.lastMessageAt, it.session.updatedAt) }
        ?.session
    val currentMatches = current == null || trimmedQuery.isEmpty() ||
        "${current.owner}/${current.repo}".contains(trimmedQuery, ignoreCase = true)
    val activities = conversations.filter { summary ->
        trimmedQuery.isEmpty() ||
            summary.session.displayTitle.contains(trimmedQuery, ignoreCase = true) ||
            "${summary.session.owner}/${summary.session.repo}".contains(trimmedQuery, ignoreCase = true)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 22.dp),
    ) {
        Text(
            text = stringResource(R.string.home_eyebrow),
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = 1.4.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.home_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.home_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text(stringResource(R.string.home_search)) },
            leadingIcon = {
                Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(18.dp))
            },
            singleLine = true,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 20.dp),
        )

        SectionHeading(
            title = stringResource(R.string.home_section_current),
            pill = if (current != null) {
                "${repoConversations.size}"
            } else {
                stringResource(R.string.home_none_pill)
            },
        )

        when {
            current != null && currentMatches -> CurrentRepoCard(
                owner = current.owner,
                repo = current.repo,
                branch = current.workingBranch ?: current.defaultBranch,
                onStart = {
                    onOpenChat(current.owner, current.repo, current.defaultBranch, "REPO", current.repoKey)
                },
                onBrowse = {
                    onOpenRepoDetail(current.owner, current.repo, current.defaultBranch)
                },
            )

            current == null -> ConnectRepoCard(onConnect = onOpenRepos)

            else -> Text(
                text = stringResource(R.string.home_search_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        }

        SectionHeading(
            title = stringResource(R.string.home_section_activity),
            pill = stringResource(R.string.home_activity_pill),
        )

        if (activities.isEmpty()) {
            Text(
                text = stringResource(R.string.home_activity_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        } else {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Column {
                    activities.forEachIndexed { index, summary ->
                        ActivityRow(
                            summary = summary,
                            onClick = {
                                val s = summary.session
                                if (s.isGeneral) {
                                    onOpenChat("", "", "", "GENERAL", s.repoKey)
                                } else {
                                    onOpenChat(s.owner, s.repo, s.defaultBranch, "REPO", s.repoKey)
                                }
                            },
                        )
                        if (index != activities.lastIndex) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                                modifier = Modifier.padding(start = 56.dp),
                            )
                        }
                    }
                }
            }
        }

        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 18.dp),
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    Icons.Rounded.Shield,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(9.dp))
                Text(
                    text = stringResource(R.string.home_hint_branch),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun SectionHeading(title: String, pill: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = title, style = MaterialTheme.typography.titleSmall)
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.background,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Text(
                text = pill,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun CurrentRepoCard(
    owner: String,
    repo: String,
    branch: String,
    onStart: () -> Unit,
    onBrowse: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.Code,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "$owner / $repo",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(9.dp))
                Text(
                    text = stringResource(R.string.home_repo_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.AccountTree,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = branch,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Button(
                    onClick = onStart,
                    colors = githubCtaButtonColors(),
                    shape = MaterialTheme.shapes.extraSmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                ) {
                    Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.home_start_coding))
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.Shield,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.home_review_commit),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onBrowse) {
                    Text(
                        text = stringResource(R.string.home_browse_files),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Spacer(Modifier.width(2.dp))
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectRepoCard(onConnect: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.home_connect_title),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.home_connect_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onConnect,
                colors = githubCtaButtonColors(),
                shape = MaterialTheme.shapes.extraSmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp),
            ) {
                Text(stringResource(R.string.home_connect_action))
            }
        }
    }
}

@Composable
private fun ActivityRow(summary: ConversationSummary, onClick: () -> Unit) {
    val session = summary.session
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.background,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier.size(32.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (session.isGeneral) Icons.Rounded.SmartToy else Icons.Rounded.Code,
                        contentDescription = null,
                        tint = if (session.isGeneral) {
                            GitHubPurple
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.size(17.dp),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = session.displayTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = if (session.isGeneral) {
                        stringResource(R.string.home_activity_general)
                    } else {
                        "${session.owner}/${session.repo} · ${stringResource(R.string.home_activity_repo)}"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = timeAgo(summary.lastMessageAt.takeIf { it > 0 } ?: session.updatedAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp, top = 3.dp),
            )
        }
    }
}

@Composable
private fun EmptyRepositoryTab(onConnect: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Rounded.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(44.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.home_connect_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.home_connect_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onConnect, colors = githubCtaButtonColors()) {
            Text(stringResource(R.string.home_connect_action))
        }
    }
}
