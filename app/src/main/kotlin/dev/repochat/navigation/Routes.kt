package dev.repochat.navigation

import kotlinx.serialization.Serializable

@Serializable
data object SettingsRoute

@Serializable
data object ChatsRoute

@Serializable
data object RepoPickerRoute

@Serializable
data class ChatRoute(
    val owner: String,
    val repo: String,
    val defaultBranch: String,
    /** GENERAL or REPO */
    val mode: String = "REPO",
    /** Stored session key — required to reopen a general chat. */
    val repoKey: String = "",
)
