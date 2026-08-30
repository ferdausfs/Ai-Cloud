package dev.repochat.ui.home

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import dev.repochat.R
import dev.repochat.ui.chats.ChatsHomeScreen
import dev.repochat.ui.repopicker.RepoPickerScreen
import dev.repochat.ui.settings.SettingsScreen

private data class TabSpec(
    val labelRes: Int,
    val icon: ImageVector,
)

/**
 * Root shell with bottom tabs (Chats / Repos / Settings). Int tab index —
 * no nested NavHost — so selection stays simple.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun HomeScaffold(
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onOpenChat: (owner: String, repo: String, defaultBranch: String, mode: String, repoKey: String) -> Unit,
    onOpenGeneralChat: () -> Unit,
    onOpenRepoDetail: (owner: String, repo: String, defaultBranch: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf(
        TabSpec(R.string.chats_title, Icons.Rounded.Code),
        TabSpec(R.string.repos_title, Icons.Rounded.Folder),
        TabSpec(R.string.tab_settings, Icons.Rounded.Settings),
    )

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(stringResource(tab.labelRes)) },
                    )
                }
            }
        },
    ) { padding ->
        val contentModifier = Modifier.padding(padding)
        when (selectedTab) {
            0 -> ChatsHomeScreen(
                onOpenConversation = { summary ->
                    val s = summary.session
                    if (s.isGeneral) {
                        onOpenChat("", "", "", "GENERAL", s.repoKey)
                    } else {
                        onOpenChat(s.owner, s.repo, s.defaultBranch, "REPO", s.repoKey)
                    }
                },
                onNewGeneral = onOpenGeneralChat,
                onNewRepoChat = { selectedTab = 1 },
                onBack = null,
                modifier = contentModifier,
            )
            1 -> RepoPickerScreen(
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
                onBack = { selectedTab = 0 },
                onOpenSettings = { selectedTab = 2 },
                onRepoSelected = { repo ->
                    onOpenRepoDetail(repo.owner, repo.name, repo.defaultBranch)
                },
                modifier = contentModifier,
            )
            else -> Box(modifier = contentModifier) {
                SettingsScreen(
                    onBrowseRepos = { selectedTab = 1 },
                    onResumeRepo = { owner, repo, defaultBranch ->
                        onOpenChat(owner, repo, defaultBranch, "REPO", "$owner/$repo")
                    },
                    onOpenGeneralChat = onOpenGeneralChat,
                    onOpenChats = { selectedTab = 0 },
                    embeddedInTabs = true,
                )
            }
        }
    }
}
