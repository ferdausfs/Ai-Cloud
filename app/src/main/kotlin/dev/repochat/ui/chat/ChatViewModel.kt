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

    private var messageJob: Job? = null
    private var turnObserveJob: Job? = null
    private var snackbarCounter = 0L

    fun start(owner: String, repo: String, defaultBranch: String) {
        val key = "$owner/$repo"
        if (key == this.repoKey) return
        this.owner = owner
        this.repo = repo
        this.repoKey = key
        this.defaultBranch = defaultBranch
        _uiState.value = ChatUiState()

        viewModelScope.launch { chatRepository.ensureSession(owner, repo, defaultBranch) }

        messageJob?.cancel()
        messageJob = viewModelScope.launch {
            chatRepository.session(key)
                .filterNotNull()
                .flatMapLatest { session ->
                    _uiState.update { it.copy(session = session) }
                    chatRepository.messages(key, session.sessionId)
                }
                .collect { messages ->
                    _uiState.update { it.copy(messages = messages) }
                }
        }

        // Mirror coordinator live state for this repo (and any in-flight turn
        // that continued while we were backgrounded).
        turnObserveJob?.cancel()
        turnObserveJob = viewModelScope.launch {
            turnCoordinator.state.collect { live ->
                if (live.repoKey.isNotEmpty() && live.repoKey != repoKey) {
                    // Another repo's turn — don't clobber this chat's UI.
                    return@collect
                }
                _uiState.update { ui ->
                    val sameRepo = live.repoKey.isEmpty() || live.repoKey == repoKey
                    val prInfo = live.prInfo
                    ui.copy(
                        typing = if (sameRepo) live.typing else ui.typing,
                        workingStep = if (sameRepo) live.workingStep else ui.workingStep,
                        approvalPending = if (sameRepo) live.approvalPending else ui.approvalPending,
                        approving = if (sameRepo) live.approving else ui.approving,
                        pendingWriteMessageId = if (sameRepo) live.pendingWriteMessageId else ui.pendingWriteMessageId,
                        liveChange = if (sameRepo) live.liveChange else ui.liveChange,
                        treeTruncated = if (sameRepo) (live.treeTruncated || ui.treeTruncated) else ui.treeTruncated,
                        // Prefer live error when the coordinator has one; keep a
                        // dismissed-null from dismissError (coordinator cleared).
                        error = if (sameRepo) live.error else ui.error,
                        canRetry = if (sameRepo) live.canRetry else ui.canRetry,
                        ciStatus = if (sameRepo) (live.ciStatus ?: ui.ciStatus) else ui.ciStatus,
                        prState = if (sameRepo && prInfo != null) PrState.Ready(prInfo) else ui.prState,
                        autoFixActive = if (sameRepo) live.autoFixActive else ui.autoFixActive,
                        autoFixAttempt = if (sameRepo) live.autoFixAttempt else ui.autoFixAttempt,
                        autoFixMaxAttempts = if (sameRepo) live.autoFixMaxAttempts else ui.autoFixMaxAttempts,
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
        val autoFix = autoFixOverride ?: state.autoFixUntilCiGreen
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
