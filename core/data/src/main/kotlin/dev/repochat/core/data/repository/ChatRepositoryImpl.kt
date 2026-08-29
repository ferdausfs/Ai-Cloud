package dev.repochat.core.data.repository

import android.util.Base64
import dev.repochat.core.data.local.ChatMessageDao
import dev.repochat.core.data.local.ChatMessageEntity
import dev.repochat.core.data.local.RepoSessionDao
import dev.repochat.core.data.local.RepoSessionEntity
import dev.repochat.core.data.local.toModel
import dev.repochat.core.domain.ChatRepository
import dev.repochat.core.model.ChatMessage
import dev.repochat.core.model.ChatRole
import dev.repochat.core.model.MessageKind
import dev.repochat.core.model.MessageStatus
import dev.repochat.core.model.PendingChange
import dev.repochat.core.model.RepoSession
import dev.repochat.core.model.SessionIdGenerator
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class ChatRepositoryImpl @Inject constructor(
    private val sessionDao: RepoSessionDao,
    private val messageDao: ChatMessageDao,
) : ChatRepository {

    override fun session(repoKey: String): Flow<RepoSession?> =
        sessionDao.observe(repoKey).map { it?.toModel() }

    override suspend fun ensureSession(owner: String, repo: String, defaultBranch: String): RepoSession {
        val repoKey = "$owner/$repo"
        sessionDao.get(repoKey)?.let { return it.toModel() }
        val session = RepoSessionEntity(
            repoKey = repoKey,
            owner = owner,
            repo = repo,
            defaultBranch = defaultBranch,
            sessionId = SessionIdGenerator.new(),
            workingBranch = null,
        )
        sessionDao.upsert(session)
        return session.toModel()
    }

    override suspend fun updateWorkingBranch(repoKey: String, branch: String) {
        sessionDao.updateBranch(repoKey, branch)
    }

    override fun messages(repoKey: String, sessionId: String): Flow<List<ChatMessage>> =
        messageDao.observe(repoKey, sessionId).map { list -> list.map { it.toModel() } }

    override suspend fun recentMessages(repoKey: String, sessionId: String, limit: Int): List<ChatMessage> =
        messageDao.recent(repoKey, sessionId, limit).asReversed().map { it.toModel() }

    override suspend fun appendUserText(repoKey: String, sessionId: String, text: String): Long =
        messageDao.insert(
            ChatMessageEntity(
                repoKey = repoKey,
                sessionId = sessionId,
                role = ChatRole.USER.name,
                kind = MessageKind.TEXT.name,
                text = text,
                status = MessageStatus.NONE.name,
                createdAt = System.currentTimeMillis(),
            )
        )

    override suspend fun appendAiText(repoKey: String, sessionId: String, text: String): Long =
        messageDao.insert(
            ChatMessageEntity(
                repoKey = repoKey,
                sessionId = sessionId,
                role = ChatRole.AI.name,
                kind = MessageKind.TEXT.name,
                text = text,
                status = MessageStatus.NONE.name,
                createdAt = System.currentTimeMillis(),
            )
        )

    override suspend fun appendAiRead(repoKey: String, sessionId: String, path: String): Long =
        messageDao.insert(
            ChatMessageEntity(
                repoKey = repoKey,
                sessionId = sessionId,
                role = ChatRole.AI.name,
                kind = MessageKind.READ_FILE.name,
                filePath = path,
                status = MessageStatus.NONE.name,
                createdAt = System.currentTimeMillis(),
            )
        )

    override suspend fun appendAiWritePending(repoKey: String, sessionId: String, change: PendingChange): Long =
        messageDao.insert(
            ChatMessageEntity(
                repoKey = repoKey,
                sessionId = sessionId,
                role = ChatRole.AI.name,
                kind = MessageKind.WRITE_FILE.name,
                filePath = change.path,
                base64Content = Base64.encodeToString(change.newContent.toByteArray(Charsets.UTF_8), Base64.NO_WRAP),
                base64Sha = change.baseSha,
                commitMessage = change.commitMessage,
                status = MessageStatus.PENDING.name,
                createdAt = System.currentTimeMillis(),
            )
        )

    override suspend fun markWrite(id: Long, status: MessageStatus, newSha: String?) {
        messageDao.updateStatus(id, status.name, newSha)
    }

    override suspend fun clearMessages(repoKey: String, sessionId: String) {
        messageDao.clear(repoKey, sessionId)
    }
}
