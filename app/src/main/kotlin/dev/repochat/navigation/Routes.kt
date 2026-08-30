package dev.repochat.navigation

import kotlinx.serialization.Serializable

/** Root shell with bottom tabs (Chats / Repos / Settings). */
@Serializable
data object HomeRoute

@Serializable
data object ChatsTab

@Serializable
data object ReposTab

@Serializable
data object SettingsTab

/** Kept for deep-links / legacy; Settings now lives under [HomeRoute]. */
@Serializable
data object SettingsRoute

@Serializable
data object RepoPickerRoute

@Serializable
data class ChatRoute(
    val owner: String,
    val repo: String,
    val defaultBranch: String,
    val mode: String = "REPO",
    val repoKey: String = "",
)

@Serializable
data class RepoDetailRoute(
    val owner: String,
    val repo: String,
    val defaultBranch: String,
)

