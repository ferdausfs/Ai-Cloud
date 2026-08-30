package dev.repochat.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.repochat.R
import dev.repochat.core.domain.ChatRepository
import dev.repochat.core.domain.CreatePullRequestUseCase
import dev.repochat.core.model.AppError
import dev.repochat.core.model.ChatAttachment
import dev.repochat.core.model.ChatMessage
import dev.repochat.core.model.ChatMode
import dev.repochat.core.model.PendingChange
import dev.repochat.core.model.PullRequestInfo
import dev.repochat.core.model.RepoSession
import dev.repochat.core.model.TurnRequest
import dev.repochat.core.model.WorkflowRunInfo
import dev.repochat.turn.AiTurnCoordinator
import dev.repochat.turn.AiTurnSnackbar
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SnackbarEvent(
    val id: Long = 0,
    val textRes: Int = 0,
    val args: List<Any> = emptyList(),
)

sealed interface PrState {
    data object None : PrState
    data object Creating : PrState
    data class Ready(val info: PullRequestInfo) : PrState
    data class Failed(val message: String) : PrState
}

/**
 * Lightweight preview of a file the user picked but has not yet sent.
 * The heavy content (text / base64) is loaded only at send time.
 */
data class PendingAttachment(
    val uriString: String,
    val displayName: String,
    val mimeType: String?,
    val isImage: Boolean,
)

data class ChatUiState(
    val session: RepoSession? = null,
    val messages: List<ChatMessage> = emptyList(),
    val typing: Boolean = false,
    val workingStep: String = "",
    val approvalPending: Boolean = false,
    val approving: Boolean = false,
    val pendingWriteMessageId: Long? = null,
    val liveChange: PendingChange? = null,
    val treeTruncated: Boolean = false,
    val error: AppError? = null,
    val canRetry: Boolean = false,
    val snackbar: SnackbarEvent = SnackbarEvent(),
    val prState: PrState = PrState.None,
    val pendingAttachment: PendingAttachment? = null,
    /** Latest known Actions run for the working branch (from check_ci_status). */
    val ciStatus: WorkflowRunInfo? = null,
    /** Opt-in per message: run AutoFixLoop until CI is green (or attempts exhausted). */
    val autoFixUntilCiGreen: Boolean = false,
    val autoFixActive: Boolean = false,
    val autoFixAttempt: Int = 0,
    val autoFixMaxAttempts: Int = 0,
)

/**
 * UI-facing ViewModel. Turn execution is delegated to [AiTurnCoordinator] so
 * work survives Activity/ViewModel teardown (paired with AiTurnService FGS).
 * Chat history still comes from Room — returning to the app shows completed turns.
 */
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val createPullRequest: CreatePullRequestUseCase,
    private val turnCoordinator: AiTurnCoordinator,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var owner: String = ""
    private var repo: String = ""
    private var repoKey: String = ""
    private var defaultBranch: String = ""
    private var mode: ChatMode = ChatMode.REPO

    private var messageJob: Job? = null
    private var turnObserveJob: Job? = null
    private var snackbarCounter = 0L

    /**
     * @param mode GENERAL or REPO
     * @param existingRepoKey when reopening a known conversation (esp. general),
     *   pass the stored `repoKey` so we don't create a duplicate session.
     */
    fun start(
        owner: String,
        repo: String,
        defaultBranch: String,
        mode: ChatMode = ChatMode.REPO,
        existingRepoKey: String = "",
    ) {
        val provisionalKey = when {
            existingRepoKey.isNotBlank() -> existingRepoKey
            mode == ChatMode.REPO -> "$owner/$repo"
            else -> ""
        }
        // Same conversation already bound (including a just-created general
        // session whose key was empty at the first start() call).
        if (this.mode == mode && this.repoKey.isNotEmpty()) {
            val sameRepo = mode == ChatMode.REPO && this.repoKey == "$owner/$repo"
            val sameGeneral = mode == ChatMode.GENERAL && (
                existingRepoKey.isBlank() || existingRepoKey == this.repoKey
            )
            if (sameRepo || sameGeneral) return
        }
        if (provisionalKey.isNotEmpty() && provisionalKey == this.repoKey && this.mode == mode) return

        this.owner = owner
        this.repo = repo
        this.defaultBranch = defaultBranch
        this.mode = mode
        this.repoKey = provisionalKey
        _uiState.value = ChatUiState()

        messageJob?.cancel()
        turnObserveJob?.cancel()

        messageJob = viewModelScope.launch {
            val session = when (mode) {
                ChatMode.REPO -> {
                    val key = "$owner/$repo"
                    this@ChatViewModel.repoKey = key
                    chatRepository.ensureSession(owner, repo, defaultBranch)
                }
                ChatMode.GENERAL -> {
                    if (existingRepoKey.isNotBlank()) {
                        val existing = chatRepository.getSession(existingRepoKey)
                        if (existing != null) {
                            this@ChatViewModel.repoKey = existing.repoKey
                            existing
                        } else {
                            val created = chatRepository.createGeneralSession()
                            this@ChatViewModel.repoKey = created.repoKey
                            created
                        }
                    } else {
                        val created = chatRepository.createGeneralSession()
                        this@ChatViewModel.repoKey = created.repoKey
                        created
                    }
                }
            }
            val boundKey = session.repoKey
            _uiState.update { it.copy(session = session) }

            chatRepository.session(boundKey)
                .filterNotNull()
                .flatMapLatest { s ->
                    _uiState.update { it.copy(session = s) }
                    chatRepository.messages(boundKey, s.sessionId)
                }
                .collect { messages ->
                    _uiState.update { it.copy(messages = messages) }
                }
        }

        // Mirror coordinator live state for this conversation (and any in-flight
        // turn that continued while we were backgrounded).
        turnObserveJob = viewModelScope.launch {
            turnCoordinator.state.collect { live ->
                val bound = repoKey
                if (bound.isNotEmpty() && live.repoKey.isNotEmpty() && live.repoKey != bound) {
                    return@collect
                }
                _uiState.update { ui ->
                    val same = bound.isEmpty() || live.repoKey.isEmpty() || live.repoKey == bound
                    val prInfo = live.prInfo
                    ui.copy(
                        typing = if (same) live.typing else ui.typing,
                        workingStep = if (same) live.workingStep else ui.workingStep,
                        approvalPending = if (same) live.approvalPending else ui.approvalPending,
                        approving = if (same) live.approving else ui.approving,
                        pendingWriteMessageId = if (same) live.pendingWriteMessageId else ui.pendingWriteMessageId,
                        liveChange = if (same) live.liveChange else ui.liveChange,
                        treeTruncated = if (same) (live.treeTruncated || ui.treeTruncated) else ui.treeTruncated,
                        error = if (same) live.error else ui.error,
                        canRetry = if (same) live.canRetry else ui.canRetry,
                        ciStatus = if (same) (live.ciStatus ?: ui.ciStatus) else ui.ciStatus,
                        prState = if (same && prInfo != null) PrState.Ready(prInfo) else ui.prState,
                        autoFixActive = if (same) live.autoFixActive else ui.autoFixActive,
                        autoFixAttempt = if (same) live.autoFixAttempt else ui.autoFixAttempt,
                        autoFixMaxAttempts = if (same) live.autoFixMaxAttempts else ui.autoFixMaxAttempts,
                    )
                }
                live.snackbar?.let { snack ->
                    when (snack) {
                        is AiTurnSnackbar.Committed -> showSnackbar(R.string.chat_committed_to, snack.branch)
                        AiTurnSnackbar.Declined -> showSnackbar(R.string.chat_declined)
                    }
                    turnCoordinator.consumeSnackbar()
                }
            }
        }
    }

    fun setPendingAttachment(attachment: PendingAttachment?) {
        _uiState.update { it.copy(pendingAttachment = attachment) }
    }

    fun clearPendingAttachment() = setPendingAttachment(null)

    fun setAutoFixUntilCiGreen(enabled: Boolean) {
        _uiState.update { it.copy(autoFixUntilCiGreen = enabled) }
    }

    fun send(text: String, attachment: ChatAttachment? = null) =
        sendInternal(text, attachment, resend = false)

    fun retry() {
        turnCoordinator.lastUserInput()?.let {
            sendInternal(
                it,
                turnCoordinator.lastAttachment(),
                resend = true,
                autoFixOverride = turnCoordinator.lastAutoFix(),
            )
        }
    }

    private fun sendInternal(
        text: String,
        attachment: ChatAttachment?,
        resend: Boolean,
        autoFixOverride: Boolean? = null,
    ) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() && attachment == null) return
        val session = _uiState.value.session ?: return
        val state = _uiState.value
        if (state.typing || state.approvalPending || state.approving) return

        val displayText = buildString {
            if (attachment != null) {
                append("📎 ").append(attachment.displayName)
                if (trimmed.isNotEmpty()) append('\n')
            }
            append(trimmed)
        }.ifBlank { "📎 ${attachment?.displayName.orEmpty()}" }

        val userText = trimmed.ifEmpty { displayText }
        val autoFix = if (mode == ChatMode.GENERAL) {
            false
        } else {
            autoFixOverride ?: state.autoFixUntilCiGreen
        }
        turnCoordinator.rememberRetry(userText, attachment, autoFix = autoFix)
        _uiState.update { it.copy(pendingAttachment = null, error = null) }

        viewModelScope.launch {
            if (!resend) {
                val label = if (autoFix) {
                    "🔁 Auto-fix until CI green\n$displayText"
                } else {
                    displayText
                }
                chatRepository.appendUserText(repoKey, session.sessionId, label)
            }
            val request = TurnRequest(
                repoKey = repoKey,
                owner = owner,
                repo = repo,
                defaultBranch = defaultBranch,
                workingBranch = session.workingBranch,
                sessionId = session.sessionId,
                userText = userText,
                attachment = attachment,
                mode = mode,
                autoFixUntilCiGreen = autoFix,
            )
            // Runs in the application-scoped coordinator + FGS — not viewModelScope.
            turnCoordinator.startTurn(request)
        }
    }

    fun approveChange() = turnCoordinator.approveChange()

    fun rejectChange() = turnCoordinator.rejectChange()

    fun createPullRequestNow() {
        val session = _uiState.value.session ?: return
        if (session.isGeneral) return
        if (_uiState.value.prState == PrState.Creating) return
        val head = session.workingBranch ?: "ai-chat/${session.sessionId}"
        _uiState.update { it.copy(prState = PrState.Creating) }
        viewModelScope.launch {
            val newState = try {
                val info = createPullRequest(
                    owner = session.owner,
                    repo = session.repo,
                    head = head,
                    base = session.defaultBranch,
                    title = "AI changes from $head",
                    body = "Changes proposed by the RepoChat AI on working branch `$head`.\n\n" +
                        "Review and merge when ready — merging is always a manual step.",
                )
                PrState.Ready(info)
            } catch (e: AppError) {
                PrState.Failed(e.userMessage)
            } catch (e: Exception) {
                PrState.Failed(
                    e.message?.takeIf { it.isNotBlank() } ?: "Could not create the pull request.",
                )
            }
            _uiState.update { it.copy(prState = newState) }
        }
    }

    fun dismissPrDialog() {
        turnCoordinator.dismissPrInfo()
        _uiState.update { it.copy(prState = PrState.None) }
    }

    fun clearConversation() {
        val session = _uiState.value.session ?: return
        viewModelScope.launch {
            chatRepository.clearMessages(repoKey, session.sessionId)
        }
    }

    fun dismissError() {
        turnCoordinator.dismissError()
        _uiState.update { it.copy(error = null) }
    }

    fun consumeTreeTruncated() {
        turnCoordinator.consumeTreeTruncated()
        _uiState.update { it.copy(treeTruncated = false) }
    }

    fun onSnackbarShown() = _uiState.update { it.copy(snackbar = SnackbarEvent()) }

    private fun showSnackbar(textRes: Int, vararg args: Any) {
        snackbarCounter++
        _uiState.update { it.copy(snackbar = SnackbarEvent(snackbarCounter, textRes, args.toList())) }
    }
}
