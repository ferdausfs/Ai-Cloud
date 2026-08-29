package dev.repochat.core.data.local

import dev.repochat.core.model.ActiveRepo
import dev.repochat.core.model.ChatMessage
import dev.repochat.core.model.ChatRole
import dev.repochat.core.model.MessageKind
import dev.repochat.core.model.MessageStatus
import dev.repochat.core.model.RepoSession

internal fun RepoSessionEntity.toModel(): RepoSession = RepoSession(
    repoKey = repoKey,
    owner = owner,
    repo = repo,
    defaultBranch = defaultBranch,
    sessionId = sessionId,
    workingBranch = workingBranch,
)

internal fun ActiveRepoEntity.toModel(): ActiveRepo = ActiveRepo(
    repoKey = repoKey,
    owner = owner,
    repo = repo,
    defaultBranch = defaultBranch,
    selectedAt = selectedAt,
)

internal fun ActiveRepo.toEntity(): ActiveRepoEntity = ActiveRepoEntity(
    id = 1,
    repoKey = repoKey,
    owner = owner,
    repo = repo,
    defaultBranch = defaultBranch,
    selectedAt = selectedAt,
)

internal fun ChatMessageEntity.toModel(): ChatMessage = ChatMessage(
    id = id,
    repoKey = repoKey,
    sessionId = sessionId,
    role = roleFrom(role),
    kind = kindFrom(kind),
    text = text,
    filePath = filePath,
    base64Content = base64Content,
    base64Sha = base64Sha,
    commitMessage = commitMessage,
    status = statusFrom(status),
    createdAt = createdAt,
)

private fun roleFrom(value: String): ChatRole =
    try {
        ChatRole.valueOf(value)
    } catch (_: IllegalArgumentException) {
        ChatRole.AI
    }

private fun kindFrom(value: String): MessageKind =
    try {
        MessageKind.valueOf(value)
    } catch (_: IllegalArgumentException) {
        MessageKind.TEXT
    }

private fun statusFrom(value: String): MessageStatus =
    try {
        MessageStatus.valueOf(value)
    } catch (_: IllegalArgumentException) {
        MessageStatus.NONE
    }
