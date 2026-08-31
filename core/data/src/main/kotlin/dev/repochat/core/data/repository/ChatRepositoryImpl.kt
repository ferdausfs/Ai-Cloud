package dev.repochat.core.data.repository

import android.util.Base64
import dev.repochat.core.data.local.ChatMessageDao
import dev.repochat.core.data.local.ChatMessageEntity
import dev.repochat.core.data.local.RepoSessionDao
import dev.repochat.core.data.local.RepoSessionEntity
import dev.repochat.core.data.local.toModel
import dev.repochat.core.domain.ChatRepository
import dev.repochat.core.model.ChatMessage
import dev.repochat.core.model.ChatMode
import dev.repochat.core.model.ChatRole
import dev.repochat.core.model.ConversationSummary
import dev.repochat.core.model.MessageKind
import dev.repochat.core.model.MessageStatus
import dev.repochat.core.model.PendingChange
import dev.repochat.core.model.RepoSession
import dev.repochat.core.model.SessionIdGenerator
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

@Singleton
class ChatRepositoryImpl @Inject constructor(
    private val sessionDao: RepoSessionDao,
    private val messageDao: ChatMessageDao,
) : ChatRepository {

    override fun session(repoKey: String): Flow<RepoSession?> =
        sessionDao.observe(repoKey).map { it?.toModel() }

    override suspend fun getSession(repoKey: String): RepoSession? =
        sessionDao.get(repoKey)?.toModel()

    override fun conversations(): Flow<List<ConversationSummary>> = flow {
        sessionDao.observeAll().collect { sessions ->
            val rows = sessions.map { entity ->
                val session = entity.toModel()
                val latest = messageDao.latestForRepo(entity.repoKey)
                val preview = latest?.let { msg ->
                    when {
                        !msg.text.isNullOrBlank() -> msg.text!!.trim().replace('\n', ' ')
                        msg.kind == MessageKind.WRITE_FILE.name ->
                            "Proposed change: ${msg.filePath.orEmpty()}"
                        msg.kind == MessageKind.READ_FILE.name ->
                            "Read ${msg.filePath.orEmpty()}"
                        else -> null
                    }?.take(120)
                }
                val at = maxOf(entity.updatedAt, latest?.createdAt ?: 0L)
                ConversationSummary(
                    session = session,
                    lastMessagePreview = preview,
                    lastMessageAt = at,
                )
            }.sortedByDescending { it.lastMessageAt }
            emit(rows)
        }
    }

    override suspend fun ensureSession(owner: String, repo: String, defaultBranch: String): RepoSession {
        val repoKey = "$owner/$repo"
        sessionDao.get(repoKey)?.let { return it.toModel() }
        val now = System.currentTimeMillis()
        val session = RepoSessionEntity(
            repoKey = repoKey,
            owner = owner,
            repo = repo,
            defaultBranch = defaultBranch,
            sessionId = SessionIdGenerator.new(),
            workingBranch = null,
            mode = ChatMode.REPO.name,
            title = null,
            updatedAt = now,
        )
        sessionDao.upsert(session)
        return session.toModel()
    }

    override suspend fun createGeneralSession(): RepoSession {
        val sessionId = SessionIdGenerator.new()
        val repoKey = "general/$sessionId"
        val now = System.currentTimeMillis()
        val session = RepoSessionEntity(
            repoKey = repoKey,
            owner = "",
            repo = "",
            defaultBranch = "",
            sessionId = sessionId,
            workingBranch = null,
            mode = ChatMode.GENERAL.name,
            title = null,
            updatedAt = now,
        )
        sessionDao.upsert(session)
        return session.toModel()
    }

    override suspend fun updateWorkingBranch(repoKey: String, branch: String) {
        sessionDao.updateBranch(repoKey, branch)
    }

    override suspend fun deleteConversation(repoKey: String) {
        messageDao.clearAllForRepo(repoKey)
        sessionDao.delete(repoKey)
    }

    override fun messages(repoKey: String, sessionId: String): Flow<List<ChatMessage>> =
        messageDao.observe(repoKey, sessionId).map { list -> list.map { it.toModel() } }

    override suspend fun recentMessages(repoKey: String, sessionId: String, limit: Int): List<ChatMessage> =
        messageDao.recent(repoKey, sessionId, limit).asReversed().map { it.toModel() }

    override suspend fun appendUserText(repoKey: String, sessionId: String, text: String): Long {
        val id = messageDao.insert(
            ChatMessageEntity(
                repoKey = repoKey,
                sessionId = sessionId,
                role = ChatRole.USER.name,
                kind = MessageKind.TEXT.name,
                text = text,
                status = MessageStatus.NONE.name,
                createdAt = System.currentTimeMillis(),
            ),
        )
        afterAppend(repoKey, text, isUser = true)
        return id
    }

    override suspend fun appendAiText(repoKey: String, sessionId: String, text: String): Long {
        val id = messageDao.insert(
            ChatMessageEntity(
                repoKey = repoKey,
                sessionId = sessionId,
                role = ChatRole.AI.name,
                kind = MessageKind.TEXT.name,
                text = text,
                status = MessageStatus.NONE.name,
                createdAt = System.currentTimeMillis(),
            ),
        )
        afterAppend(repoKey, text, isUser = false)
        return id
    }

    override suspend fun appendAiRead(repoKey: String, sessionId: String, path: String): Long {
        val id = messageDao.insert(
            ChatMessageEntity(
                repoKey = repoKey,
                sessionId = sessionId,
                role = ChatRole.AI.name,
                kind = MessageKind.READ_FILE.name,
                filePath = path,
                status = MessageStatus.NONE.name,
                createdAt = System.currentTimeMillis(),
            ),
        )
        sessionDao.touch(repoKey, System.currentTimeMillis())
        return id
    }

    override suspend fun appendAiWritePending(repoKey: String, sessionId: String, change: PendingChange): Long {
        val id = messageDao.insert(
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
            ),
        )
        sessionDao.touch(repoKey, System.currentTimeMillis())
        return id
    }

    override suspend fun markWrite(id: Long, status: MessageStatus, newSha: String?) {
        messageDao.updateStatus(id, status.name, newSha)
    }

    override suspend fun clearMessages(repoKey: String, sessionId: String) {
        messageDao.clear(repoKey, sessionId)
        sessionDao.touch(repoKey, System.currentTimeMillis())
    }

    override suspend fun hasApprovedWrite(repoKey: String, sessionId: String): Boolean =
        messageDao.countApprovedWrites(repoKey, sessionId) > 0

    private suspend fun afterAppend(repoKey: String, text: String, isUser: Boolean) {
        val now = System.currentTimeMillis()
        sessionDao.touch(repoKey, now)
        if (isUser) {
            val existing = sessionDao.get(repoKey)
            if (existing != null && existing.title.isNullOrBlank()) {
                val title = text.trim().replace('\n', ' ').take(60)
                    .ifBlank { if (existing.mode == ChatMode.GENERAL.name) "General chat" else existing.repoKey }
                sessionDao.updateTitle(repoKey, title)
            }
        }
    }
}
