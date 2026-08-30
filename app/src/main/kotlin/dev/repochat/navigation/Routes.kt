package dev.repochat.navigation

import kotlinx.serialization.Serializable

@Serializable
data object SettingsRoute

@Serializable
data object RepoPickerRoute

@Serializable
data class ChatRoute(
    val owner: String,
    val repo: String,
    val defaultBranch: String,
)
