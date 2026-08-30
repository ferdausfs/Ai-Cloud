package dev.repochat.core.model

/** User-configurable secrets/settings, persisted in EncryptedSharedPreferences. */
data class AppSettings(
    val ollamaKey: String = "",
    val modelName: String = "",
    val githubPat: String = "",
)

data class RepoSummary(
    val id: Long,
    val name: String,
    val fullName: String,
    val isPrivate: Boolean,
    val description: String?,
    val language: String?,
    val updatedAtMillis: Long?,
    val defaultBranch: String,
    val htmlUrl: String,
) {
    val owner: String get() = fullName.substringBefore('/')
}

data class ActiveRepo(
    val repoKey: String,
    val owner: String,
    val repo: String,
    val defaultBranch: String,
    val selectedAt: Long,
)

/**
 * One AI editing session per repository. The session id names the working
 * branch (`ai-chat/{sessionId}`) that all AI commits go to — never main.
 */
data class RepoSession(
    val repoKey: String,
    val owner: String,
    val repo: String,
    val defaultBranch: String,
    val sessionId: String,
    val workingBranch: String?,
)

data class GitFile(
    val path: String,
    val content: String,
    val sha: String,
    val sizeBytes: Long,
    val isBinary: Boolean,
)

data class TreeEntry(val path: String, val type: String)

data class RepoFileTree(val entries: List<TreeEntry>, val truncated: Boolean)

enum class ChatRole { USER, AI }

enum class MessageKind { TEXT, READ_FILE, WRITE_FILE }

enum class MessageStatus { NONE, PENDING, APPROVED, REJECTED }

data class ChatMessage(
    val id: Long,
    val repoKey: String,
    val sessionId: String,
    val role: ChatRole,
    val kind: MessageKind,
    val text: String?,
    val filePath: String?,
    val base64Content: String?,
    val base64Sha: String?,
    val commitMessage: String?,
    val status: MessageStatus,
    val createdAt: Long,
)

/** A proposed file change waiting for explicit user approval before commit. */
data class PendingChange(
    val path: String,
    val oldContent: String,
    val newContent: String,
    val baseSha: String?,
    val branch: String,
    val commitMessage: String,
    val isNew: Boolean,
    val additions: Int,
    val removals: Int,
)

data class CommitResult(val path: String, val newSha: String)

data class PullRequestInfo(val number: Long, val htmlUrl: String, val title: String)

data class ConnectionResult(val ok: Boolean, val detail: String)

/**
 * A file the user attached to a chat turn before sending. Text attachments
 * contribute their content to the model prompt; image attachments contribute
 * either vision bytes (when the model supports it) or a plain-text note.
 */
data class ChatAttachment(
    val displayName: String,
    val mimeType: String?,
    /** UTF-8 text for text/code files; null for binary/image attachments. */
    val textContent: String? = null,
    /** Raw base64 (no data-URI prefix) for image attachments; null otherwise. */
    val imageBase64: String? = null,
) {
    val isImage: Boolean
        get() = mimeType?.startsWith("image/") == true || imageBase64 != null
}

data class TurnRequest(
    val repoKey: String,
    val owner: String,
    val repo: String,
    val defaultBranch: String,
    val workingBranch: String?,
    val sessionId: String,
    val userText: String,
    val attachment: ChatAttachment? = null,
)

/** Progress events emitted while an AI turn runs. */
sealed interface TurnEvent {
    data class Working(val step: String) : TurnEvent
    data class TreeReady(val truncated: Boolean) : TurnEvent
    data class ReadingFile(val path: String) : TurnEvent
    data class Reply(val text: String) : TurnEvent
    data class ProposeWrite(val messageId: Long, val change: PendingChange) : TurnEvent
    data class WriteCommitted(val messageId: Long, val change: PendingChange) : TurnEvent
    data class WriteDeclined(val messageId: Long, val change: PendingChange) : TurnEvent
    data class Error(val error: AppError) : TurnEvent
}
