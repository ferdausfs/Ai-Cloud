package dev.repochat.turn

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.repochat.R
import dev.repochat.core.domain.AiTurnRunner
import dev.repochat.core.domain.ChatRepository
import dev.repochat.core.model.AppError
import dev.repochat.core.model.ChatAttachment
import dev.repochat.core.model.MessageStatus
import dev.repochat.core.model.PendingChange
import dev.repochat.core.model.PullRequestInfo
import dev.repochat.core.model.TurnEvent
import dev.repochat.core.model.TurnRequest
import dev.repochat.core.model.WorkflowRunInfo
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Hosts in-flight AI turns in an application-scoped coroutine so the work is
 * not cancelled when ChatViewModel / the Activity are destroyed. Paired with
 * [AiTurnService] (foreground) so OEM battery savers keep the network alive.
 *
 * Agent logic stays in [AiTurnRunner] — this only owns *where* it runs.
 */
@Singleton
class AiTurnCoordinator @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val turnRunner: AiTurnRunner,
    private val chatRepository: ChatRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(AiTurnLiveState())
    val state: StateFlow<AiTurnLiveState> = _state.asStateFlow()

    /** Gate the orchestrator waits on when a write proposal is shown. */
    private val approvalFlow = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)

    private var turnJob: Job? = null
    private var lastUserInput: String? = null
    private var lastAttachment: ChatAttachment? = null

    fun lastUserInput(): String? = lastUserInput
    fun lastAttachment(): ChatAttachment? = lastAttachment

    fun rememberRetry(userInput: String, attachment: ChatAttachment?) {
        lastUserInput = userInput
        lastAttachment = attachment
    }

    /**
     * Starts (or no-ops if already running) a turn. Spawns [AiTurnService] for
     * the duration of the work so backgrounding the app does not kill the call.
     */
    fun startTurn(request: TurnRequest) {
        if (turnJob?.isActive == true) return

        _state.update {
            it.copy(
                active = true,
                repoKey = request.repoKey,
                owner = request.owner,
                repo = request.repo,
                defaultBranch = request.defaultBranch,
                sessionId = request.sessionId,
                typing = true,
                workingStep = appContext.getString(R.string.turn_step_starting),
                error = null,
                canRetry = false,
                liveChange = null,
                pendingWriteMessageId = null,
                approvalPending = false,
                approving = false,
                treeTruncated = false,
                prInfo = null,
            )
        }

        AiTurnService.start(appContext, request.owner, request.repo, request.defaultBranch)

        turnJob = scope.launch {
            try {
                turnRunner.runTurn(request, approvalFlow).collect { event ->
                    handleEvent(event)
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        typing = false,
                        approvalPending = false,
                        approving = false,
                        error = when (e) {
                            is AppError -> e
                            else -> AppError.Network(
                                "Something went wrong: ${e.message?.takeIf { m -> m.isNotBlank() } ?: "unexpected error"}",
                            )
                        },
                        canRetry = lastUserInput != null,
                        active = false,
                        workingStep = "",
                    )
                }
            } finally {
                // Keep FGS up while waiting for Approve/Reject so the process
                // stays warm; stop it once the turn is fully idle.
                val stillWaiting = _state.value.approvalPending || _state.value.approving
                if (!stillWaiting) {
                    _state.update { it.copy(active = false, typing = false) }
                    AiTurnService.stop(appContext)
                }
            }
        }
    }

    fun approveChange() {
        if (!_state.value.approvalPending) return
        _state.update { it.copy(approvalPending = false, approving = true) }
        scope.launch { approvalFlow.emit(true) }
    }

    fun rejectChange() {
        if (!_state.value.approvalPending) return
        _state.update { it.copy(approvalPending = false) }
        scope.launch { approvalFlow.emit(false) }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun consumeTreeTruncated() = _state.update { it.copy(treeTruncated = false) }

    fun dismissPrInfo() = _state.update { it.copy(prInfo = null) }

    fun consumeSnackbar() = _state.update { it.copy(snackbar = null) }

    private suspend fun handleEvent(event: TurnEvent) {
        when (event) {
            is TurnEvent.Working ->
                _state.update { it.copy(workingStep = event.step) }

            is TurnEvent.TreeReady ->
                if (event.truncated) _state.update { it.copy(treeTruncated = true) }

            is TurnEvent.ReadingFile -> Unit

            is TurnEvent.Reply ->
                _state.update {
                    it.copy(
                        typing = false,
                        workingStep = "",
                        approvalPending = false,
                        approving = false,
                        active = false,
                    )
                }

            is TurnEvent.ProposeWrite ->
                _state.update {
                    it.copy(
                        typing = false,
                        approvalPending = true,
                        liveChange = event.change,
                        pendingWriteMessageId = event.messageId,
                        // Keep service/active while waiting for user decision.
                        active = true,
                        workingStep = appContext.getString(R.string.turn_step_awaiting_approval),
                    )
                }

            is TurnEvent.WriteCommitted -> {
                _state.update {
                    it.copy(
                        approvalPending = false,
                        approving = false,
                        liveChange = null,
                        pendingWriteMessageId = null,
                        active = false,
                        typing = false,
                        workingStep = "",
                        snackbar = AiTurnSnackbar.Committed(event.change.branch),
                    )
                }
                AiTurnService.stop(appContext)
            }

            is TurnEvent.WriteDeclined -> {
                _state.update {
                    it.copy(
                        approvalPending = false,
                        approving = false,
                        liveChange = null,
                        pendingWriteMessageId = null,
                        active = false,
                        typing = false,
                        workingStep = "",
                        snackbar = AiTurnSnackbar.Declined,
                    )
                }
                AiTurnService.stop(appContext)
            }

            is TurnEvent.PullRequestCreated ->
                _state.update { it.copy(prInfo = event.info) }

            is TurnEvent.CiStatus ->
                _state.update { it.copy(ciStatus = event.run) }

            is TurnEvent.Error -> {
                val pendingId = _state.value.pendingWriteMessageId
                if (pendingId != null) {
                    chatRepository.markWrite(pendingId, MessageStatus.REJECTED, null)
                }
                _state.update {
                    it.copy(
                        typing = false,
                        approvalPending = false,
                        approving = false,
                        liveChange = null,
                        pendingWriteMessageId = null,
                        error = event.error,
                        canRetry = lastUserInput != null,
                        active = false,
                        workingStep = "",
                    )
                }
                AiTurnService.stop(appContext)
            }
        }
    }
}

/** Live turn progress shared between [AiTurnService] and [dev.repochat.ui.chat.ChatViewModel]. */
data class AiTurnLiveState(
    val active: Boolean = false,
    val repoKey: String = "",
    val owner: String = "",
    val repo: String = "",
    val defaultBranch: String = "",
    val sessionId: String = "",
    val typing: Boolean = false,
    val workingStep: String = "",
    val approvalPending: Boolean = false,
    val approving: Boolean = false,
    val pendingWriteMessageId: Long? = null,
    val liveChange: PendingChange? = null,
    val treeTruncated: Boolean = false,
    val error: AppError? = null,
    val canRetry: Boolean = false,
    val prInfo: PullRequestInfo? = null,
    val ciStatus: WorkflowRunInfo? = null,
    val snackbar: AiTurnSnackbar? = null,
)

sealed interface AiTurnSnackbar {
    data class Committed(val branch: String) : AiTurnSnackbar
    data object Declined : AiTurnSnackbar
}
