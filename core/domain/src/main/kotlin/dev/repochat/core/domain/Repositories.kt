package dev.repochat.core.domain

import dev.repochat.core.model.AppSettings
import dev.repochat.core.model.ActiveRepo
import dev.repochat.core.model.ChatMessage
import dev.repochat.core.model.MessageStatus
import dev.repochat.core.model.PendingChange
import dev.repochat.core.model.RepoSession
import kotlinx.coroutines.flow.Flow

/**
 * Room-backed chat persistence: one session per repository (which also names
 * the working branch) plus the message history for that session.
 */
interface ChatRepository {

    fun session(repoKey: String): Flow<RepoSession?>

    suspend fun ensureSession(owner: String, repo: String, defaultBranch: String): RepoSession

    suspend fun updateWorkingBranch(repoKey: String, branch: String)

    fun messages(repoKey: String, sessionId: String): Flow<List<ChatMessage>>

    suspend fun recentMessages(repoKey: String, sessionId: String, limit: Int): List<ChatMessage>

    suspend fun appendUserText(repoKey: String, sessionId: String, text: String): Long

    suspend fun appendAiText(repoKey: String, sessionId: String, text: String): Long

    suspend fun appendAiRead(repoKey: String, sessionId: String, path: String): Long

    suspend fun appendAiWritePending(repoKey: String, sessionId: String, change: PendingChange): Long

    suspend fun markWrite(id: Long, status: MessageStatus, newSha: String?)

    suspend fun clearMessages(repoKey: String, sessionId: String)
}

/** The repository the user is currently chatting with. */
interface ActiveRepoRepository {
    val active: Flow<ActiveRepo?>
    suspend fun set(repo: ActiveRepo)
    suspend fun clear()
}

/** API keys / model name, persisted with EncryptedSharedPreferences. */
interface SettingsRepository {
    val settings: Flow<AppSettings>

    /** Non-suspending read of the latest cached value (OkHttp interceptors). */
    fun cached(): AppSettings

    suspend fun current(): AppSettings

    suspend fun save(settings: AppSettings)
}
