package dev.repochat.turn

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.repochat.R
import dev.repochat.core.domain.AiTurnRunner
import dev.repochat.core.domain.AutoFixLoop
import dev.repochat.core.domain.ChatRepository
import dev.repochat.core.model.AppError
import dev.repochat.core.model.AutoFixEvent
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
import kotlinx.coroutines.flow.Flow
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
 * Agent logic stays in [AiTurnRunner] / [AutoFixLoop] — this only owns *where*
 * it runs and mirrors progress into [state] for the UI + notification.
 */
@Singleton
class AiTurnCoordinator @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val turnRunner: AiTurnRunner,
    private val autoFixLoop: AutoFixLoop,
    private val chatRepository: ChatRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(AiTurnLiveState())
    val state: StateFlow<AiTurnLiveState> = _state.asStateFlow()

    /** Gate the orchestrator waits on when a write proposal is shown (single-turn). */
    private val approvalFlow = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)

    private var turnJob: Job? = null
    private var lastUserInput: String? = null
    private var lastAttachment: ChatAttachment? = null
    private var lastAutoFix: Boolean = false

    fun lastUserInput(): String? = lastUserInput
    fun lastAttachment(): ChatAttachment? = lastAttachment
    fun lastAutoFix(): Boolean = lastAutoFix

    fun rememberRetry(userInput: String, attachment: ChatAttachment?, autoFix: Boolean = false) {
        lastUserInput = userInput
        lastAttachment = attachment
        lastAutoFix = autoFix
    }

    /**
     * Starts (or no-ops if already running) a turn. Spawns [AiTurnService] for
     * the duration of the work so backgrounding the app does not kill the call.
     * When [TurnRequest.autoFixUntilCiGreen] is true, runs [AutoFixLoop] instead
     * of a single turn so CI can be polled for several minutes under the FGS.
     */
    fun startTurn(request: TurnRequest) {
        if (turnJob?.isActive == true) return

        val autoFix = request.autoFixUntilCiGreen
        _state.update {
            it.copy(
                active = true,
                autoFixActive = autoFix,
                autoFixAttempt = if (autoFix) 0 else 0,
                autoFixMaxAttempts = if (autoFix) {
                    request.autoFixMaxAttempts.coerceIn(1, 10)
                } else {
                    0
                },
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

        val events: Flow<TurnEvent> = if (autoFix) {
            autoFixLoop.run(request)
        } else {
            turnRunner.runTurn(request, approvalFlow)
        }

        turnJob = scope.launch {
            try {
                events.collect { event ->
                    handleEvent(event, autoFix = autoFix)
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        typing = false,
                        approvalPending = false,
                        approving = false,
                        autoFixActive = false,
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
                    _state.update {
                        it.copy(active = false, typing = false, autoFixActive = false)
                    }
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

    private suspend fun handleEvent(event: TurnEvent, autoFix: Boolean) {
        when (event) {
            is TurnEvent.Working ->
                _state.update { it.copy(workingStep = event.step, typing = true, active = true) }

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
                        // Auto-fix may still continue after a mid-loop reply.
                        active = autoFix && it.autoFixActive,
                        autoFixActive = autoFix && it.autoFixActive,
                    )
                }

            is TurnEvent.ProposeWrite -> {
                if (autoFix) {
                    // AutoFixLoop auto-approves; show the pending write but don't
                    // block the UI on a manual gate.
                    _state.update {
                        it.copy(
                            typing = true,
                            approvalPending = false,
                            approving = true,
                            liveChange = event.change,
                            pendingWriteMessageId = event.messageId,
                            active = true,
                            workingStep = appContext.getString(
                                R.string.turn_step_auto_committing,
                                event.change.path,
                            ),
                        )
                    }
                } else {
                    _state.update {
                        it.copy(
                            typing = false,
                            approvalPending = true,
                            liveChange = event.change,
                            pendingWriteMessageId = event.messageId,
                            active = true,
                            workingStep = appContext.getString(R.string.turn_step_awaiting_approval),
                        )
                    }
                }
            }

            is TurnEvent.WriteCommitted -> {
                _state.update {
                    it.copy(
                        approvalPending = false,
                        approving = false,
                        liveChange = null,
                        pendingWriteMessageId = null,
                        // Stay active under auto-fix so FGS keeps running for CI.
                        active = autoFix,
                        typing = autoFix,
                        workingStep = if (autoFix) {
                            appContext.getString(R.string.turn_step_waiting_ci)
                        } else {
                            ""
                        },
                        snackbar = if (autoFix) {
                            null
                        } else {
                            AiTurnSnackbar.Committed(event.change.branch)
                        },
                    )
                }
                if (!autoFix) {
                    AiTurnService.stop(appContext)
                }
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
                        autoFixActive = false,
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

            is TurnEvent.AutoFixProgress -> handleAutoFixProgress(event.event)

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
                        autoFixActive = false,
                        workingStep = "",
                    )
                }
                AiTurnService.stop(appContext)
            }
        }
    }

    private fun handleAutoFixProgress(event: AutoFixEvent) {
        when (event) {
            is AutoFixEvent.AttemptStarted ->
                _state.update {
                    it.copy(
                        autoFixActive = true,
                        autoFixAttempt = event.attempt,
                        autoFixMaxAttempts = event.maxAttempts,
                        typing = true,
                        active = true,
                        workingStep = appContext.getString(
                            R.string.turn_step_auto_attempt,
                            event.attempt,
                            event.maxAttempts,
                        ),
                    )
                }

            is AutoFixEvent.Committed ->
                _state.update {
                    it.copy(
                        typing = true,
                        active = true,
                        workingStep = appContext.getString(
                            R.string.turn_step_auto_committed,
                            event.attempt,
                            it.autoFixMaxAttempts,
                        ),
                    )
                }

            is AutoFixEvent.CiPending ->
                _state.update {
                    it.copy(
                        typing = true,
                        active = true,
                        ciStatus = event.run ?: it.ciStatus,
                        workingStep = appContext.getString(
                            R.string.turn_step_auto_waiting_ci,
                            event.attempt,
                            it.autoFixMaxAttempts,
                        ),
                    )
                }

            is AutoFixEvent.CiPassed ->
                _state.update {
                    it.copy(
                        typing = false,
                        active = false,
                        autoFixActive = false,
                        ciStatus = event.run,
                        workingStep = "",
                    )
                }

            is AutoFixEvent.CiFailed ->
                _state.update {
                    it.copy(
                        typing = true,
                        active = true,
                        ciStatus = event.run ?: it.ciStatus,
                        workingStep = appContext.getString(
                            R.string.turn_step_auto_ci_failed,
                            event.attempt,
                            it.autoFixMaxAttempts,
                        ),
                    )
                }

            is AutoFixEvent.GaveUp ->
                _state.update {
                    it.copy(
                        typing = false,
                        active = false,
                        autoFixActive = false,
                        workingStep = "",
                    )
                }

            is AutoFixEvent.Error ->
                _state.update {
                    it.copy(
                        typing = false,
                        active = false,
                        autoFixActive = false,
                        error = event.error,
                        canRetry = lastUserInput != null,
                        workingStep = "",
                    )
                }
        }
    }
}

/** Live turn progress shared between [AiTurnService] and [dev.repochat.ui.chat.ChatViewModel]. */
data class AiTurnLiveState(
    val active: Boolean = false,
    val autoFixActive: Boolean = false,
    val autoFixAttempt: Int = 0,
    val autoFixMaxAttempts: Int = 0,
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
