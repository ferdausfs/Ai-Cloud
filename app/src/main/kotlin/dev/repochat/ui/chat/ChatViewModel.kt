package dev.repochat.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.repochat.R
import dev.repochat.core.domain.AiTurnRunner
import dev.repochat.core.domain.ChatRepository
import dev.repochat.core.domain.CreatePullRequestUseCase
import dev.repochat.core.model.AppError
import dev.repochat.core.model.ChatMessage
import dev.repochat.core.model.MessageStatus
import dev.repochat.core.model.PendingChange
import dev.repochat.core.model.PullRequestInfo
import dev.repochat.core.model.RepoSession
import dev.repochat.core.model.TurnEvent
import dev.repochat.core.model.TurnRequest
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
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
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val turnRunner: AiTurnRunner,
    private val createPullRequest: CreatePullRequestUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var owner: String = ""
    private var repo: String = ""
    private var repoKey: String = ""
    private var defaultBranch: String = ""
    private var lastUserInput: String? = null

    private var messageJob: Job? = null
    private var snackbarCounter = 0L

    /** Gate the orchestrator waits on when a write proposal is shown. */
    private val approvalFlow = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)

    fun start(owner: String, repo: String, defaultBranch: String) {
        val key = "$owner/$repo"
        if (key == this.repoKey) return
        this.owner = owner
        this.repo = repo
        this.repoKey = key
        this.defaultBranch = defaultBranch
        this.lastUserInput = null
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
    }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val session = _uiState.value.session ?: return
        val state = _uiState.value
        if (state.typing || state.approvalPending || state.approving) return

        lastUserInput = trimmed
        viewModelScope.launch {
            chatRepository.appendUserText(repoKey, session.sessionId, trimmed)
            runTurn(trimmed, session)
        }
    }

    fun retry() {
        lastUserInput?.let { send(it) }
    }

    fun approveChange() {
        if (!_uiState.value.approvalPending) return
        _uiState.update { it.copy(approvalPending = false, approving = true) }
        viewModelScope.launch { approvalFlow.emit(true) }
    }

    fun rejectChange() {
        if (!_uiState.value.approvalPending) return
        _uiState.update { it.copy(approvalPending = false) }
        viewModelScope.launch { approvalFlow.emit(false) }
    }

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
                PrState.Failed(e.message ?: "Could not create the pull request.")
            }
            _uiState.update { it.copy(prState = newState) }
        }
    }

    fun dismissPrDialog() = _uiState.update { it.copy(prState = PrState.None) }

    fun clearConversation() {
        val session = _uiState.value.session ?: return
        viewModelScope.launch {
            chatRepository.clearMessages(repoKey, session.sessionId)
        }
    }

    fun dismissError() = _uiState.update { it.copy(error = null) }

    fun consumeTreeTruncated() = _uiState.update { it.copy(treeTruncated = false) }

    fun onSnackbarShown() = _uiState.update { it.copy(snackbar = SnackbarEvent()) }

    private fun showSnackbar(textRes: Int, vararg args: Any) {
        snackbarCounter++
        _uiState.update { it.copy(snackbar = SnackbarEvent(snackbarCounter, textRes, args.toList())) }
    }

    private fun runTurn(userText: String, session: RepoSession) {
        _uiState.update {
            it.copy(
                typing = true,
                workingStep = "",
                error = null,
                canRetry = false,
                liveChange = null,
                pendingWriteMessageId = null,
                treeTruncated = false,
            )
        }
        val request = TurnRequest(
            repoKey = repoKey,
            owner = owner,
            repo = repo,
            defaultBranch = defaultBranch,
            workingBranch = session.workingBranch,
            sessionId = session.sessionId,
            userText = userText,
        )
        viewModelScope.launch {
            turnRunner.runTurn(request, approvalFlow).collect { event ->
                when (event) {
                    is TurnEvent.Working ->
                        _uiState.update { it.copy(workingStep = event.step) }

                    is TurnEvent.TreeReady ->
                        if (event.truncated) _uiState.update { it.copy(treeTruncated = true) }

                    is TurnEvent.ReadingFile -> Unit // surfaced via the persisted read card

                    is TurnEvent.Reply -> finishTurn()

                    is TurnEvent.ProposeWrite -> _uiState.update {
                        it.copy(
                            typing = false,
                            approvalPending = true,
                            liveChange = event.change,
                            pendingWriteMessageId = event.messageId,
                        )
                    }

                    is TurnEvent.WriteCommitted -> {
                        _uiState.update {
                            it.copy(
                                approvalPending = false,
                                approving = false,
                                liveChange = null,
                                pendingWriteMessageId = null,
                            )
                        }
                        showSnackbar(R.string.chat_committed_to, event.change.branch)
                    }

                    is TurnEvent.WriteDeclined -> {
                        _uiState.update {
                            it.copy(
                                approvalPending = false,
                                approving = false,
                                liveChange = null,
                                pendingWriteMessageId = null,
                            )
                        }
                        showSnackbar(R.string.chat_declined)
                    }

                    is TurnEvent.Error -> {
                        // If the failure happened while a proposal was waiting,
                        // the approval gate is gone: mark the pending card as
                        // declined so it never looks actionable again.
                        val pendingId = _uiState.value.pendingWriteMessageId
                        if (pendingId != null) {
                            viewModelScope.launch {
                                chatRepository.markWrite(pendingId, MessageStatus.REJECTED, null)
                            }
                        }
                        _uiState.update {
                            it.copy(
                                typing = false,
                                approvalPending = false,
                                approving = false,
                                liveChange = null,
                                pendingWriteMessageId = null,
                                error = event.error,
                                canRetry = lastUserInput != null,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun finishTurn() {
        _uiState.update {
            it.copy(typing = false, workingStep = "", approvalPending = false, approving = false)
        }
    }
}
