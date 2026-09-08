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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
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

    /**
     * Gate the orchestrator waits on when a write proposal is shown (single-turn).
     *
     * A [MutableStateFlow] (not a SharedFlow with replay=0) so an Approve/Reject
     * tap that lands *before* the orchestrator subscribes is never silently
     * dropped — the orchestrator's `approval.first()` receives the current
     * value immediately. Stale decisions from a previous turn are drained in
     * [startTurn] so a leftover decision can never auto-approve the next turn.
     */
    private val approvalFlow = MutableStateFlow<Boolean?>(null)

    /** Non-null decisions only — what the orchestrator subscribes to. */
    private val approvalDecisions: Flow<Boolean> = approvalFlow.filterNotNull()

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
     * Starts a turn. Returns false (and does nothing) when another turn is
     * already running — including one in a *different* conversation (audit
     * BUG-101 / AUD-002: callers must surface this to the user, never silently
     * drop the message). Spawns [AiTurnService] for the duration of the work so
     * backgrounding the app does not kill the call. When
     * [TurnRequest.autoFixUntilCiGreen] is true, runs [AutoFixLoop] instead of
     * a single turn so CI can be polled for several minutes under the FGS.
     */
    fun startTurn(request: TurnRequest): Boolean {
        val live = _state.value
        if (turnJob?.isActive == true ||
            !dev.repochat.core.domain.TurnGate.canStartTurn(
                currentRepoKey = request.repoKey,
                liveRepoKey = live.repoKey,
                liveActive = live.active || live.approvalPending || live.approving,
            )
        ) {
            return false
        }

        // General chat never runs the CI auto-fix loop (no repo tools).
        val autoFix = request.autoFixUntilCiGreen && !request.isGeneral
        // Drain any stale decision left over from a previous turn.
        approvalFlow.value = null
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
                pendingPr = null,
                approvalPending = false,
                approving = false,
                treeTruncated = false,
                prInfo = null,
            )
        }

        AiTurnService.start(
            appContext,
            owner = request.owner,
            repo = request.repo,
            defaultBranch = request.defaultBranch,
            mode = request.mode.name,
            repoKey = request.repoKey,
        )

        val events: Flow<TurnEvent> = if (autoFix) {
            autoFixLoop.run(request)
        } else {
            turnRunner.runTurn(request, approvalDecisions)
        }

        turnJob = scope.launch {
            try {
                events.collect { event ->
                    handleEvent(event, autoFix = autoFix)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Explicit cancel (Stop button / cancelTurn) or scope teardown
                // is not an error — never surface it as "Something went wrong".
                throw e
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
        return true
    }

    /**
     * Cancels the in-flight turn (audit BUG-201). Refused while a commit is
     * mid-flight ([AiTurnLiveState.approving]) so Room status can never
     * disagree with what actually landed on GitHub. Any PENDING write row is
     * marked REJECTED, an honest "stopped by user" note is appended, the
     * auto-fix attempt counter is reset, and the foreground service is stopped.
     * Safe to call any time — including when no turn is running (no-op).
     */
    fun cancelTurn() {
        val st = _state.value
        if (st.approving) return // commit in flight — wait for its outcome
        val job = turnJob
        val busy = st.active || st.typing || st.approvalPending || st.approving
        if (job == null && !busy) return

        val pendingId = st.pendingWriteMessageId
        val repoKey = st.repoKey
        val sessionId = st.sessionId
        val wasApprovalPending = st.approvalPending

        // A pending decision must not answer a future gate.
        approvalFlow.value = null

        job?.cancel()
        turnJob = null
        _state.update {
            it.copy(
                active = false,
                typing = false,
                approvalPending = false,
                approving = false,
                autoFixActive = false,
                autoFixAttempt = 0,
                liveChange = null,
                pendingWriteMessageId = null,
                pendingPr = null,
                workingStep = "",
                error = null,
                canRetry = false,
            )
        }
        scope.launch {
            if (pendingId != null) {
                chatRepository.markWrite(pendingId, MessageStatus.REJECTED, null)
            }
            if (repoKey.isNotBlank() && sessionId.isNotBlank()) {
                val note = if (wasApprovalPending) {
                    "Stopped by user — the pending proposal was not applied."
                } else {
                    appContext.getString(R.string.turn_cancelled_note)
                }
                chatRepository.appendAiText(repoKey, sessionId, note)
            }
        }
        AiTurnService.stop(appContext)
    }

    fun approveChange() {
        if (!_state.value.approvalPending) return
        _state.update { it.copy(approvalPending = false, approving = true) }
        // StateFlow: thread-safe, never dropped, visible to a later subscriber.
        approvalFlow.value = true
    }

    fun rejectChange() {
        if (!_state.value.approvalPending) return
        _state.update { it.copy(approvalPending = false) }
        approvalFlow.value = false
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
                // Gate resolved — drain the consumed decision.
                approvalFlow.value = null
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
                // Drain the consumed decision so it can never answer a later gate.
                approvalFlow.value = null
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

            is TurnEvent.ProposePullRequest -> {
                if (autoFix) {
                    // AutoFixLoop pre-arms approval (autonomous opt-in mode):
                    // nothing to gate in the UI.
                    _state.update {
                        it.copy(
                            typing = true,
                            active = true,
                            workingStep = "Creating pull request",
                        )
                    }
                } else {
                    _state.update {
                        it.copy(
                            typing = false,
                            approvalPending = true,
                            approving = false,
                            liveChange = null,
                            pendingWriteMessageId = null,
                            pendingPr = PullRequestProposal(
                                title = event.title,
                                body = event.body,
                            ),
                            active = true,
                            workingStep = appContext.getString(R.string.turn_step_pr_approval),
                        )
                    }
                }
            }

            is TurnEvent.PullRequestCreated -> {
                // Gate resolved — drain the decision (single gate per turn).
                approvalFlow.value = null
                _state.update {
                    it.copy(
                        approvalPending = false,
                        approving = false,
                        pendingPr = null,
                        prInfo = event.info,
                        typing = false,
                        active = false,
                        workingStep = "",
                    )
                }
                AiTurnService.stop(appContext)
            }

            is TurnEvent.PullRequestDeclined -> {
                approvalFlow.value = null
                _state.update {
                    it.copy(
                        approvalPending = false,
                        approving = false,
                        pendingPr = null,
                        active = false,
                        typing = false,
                        autoFixActive = false,
                        workingStep = "",
                        snackbar = AiTurnSnackbar.Declined,
                    )
                }
                AiTurnService.stop(appContext)
            }

            is TurnEvent.CiStatus ->
                _state.update { it.copy(ciStatus = event.run) }

            is TurnEvent.ProviderNote -> Unit

            is TurnEvent.AutoFixProgress -> handleAutoFixProgress(event.event)

            is TurnEvent.Error -> {
                approvalFlow.value = null
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
                        pendingPr = null,
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
    /** Model-proposed PR awaiting the user's Create/Decline decision. */
    val pendingPr: PullRequestProposal? = null,
    val treeTruncated: Boolean = false,
    val error: AppError? = null,
    val canRetry: Boolean = false,
    val prInfo: PullRequestInfo? = null,
    val ciStatus: WorkflowRunInfo? = null,
    val snackbar: AiTurnSnackbar? = null,
)

/** A PR the model proposed; shown as a confirmation card before creation. */
data class PullRequestProposal(
    val title: String,
    val body: String,
)

sealed interface AiTurnSnackbar {
    data class Committed(val branch: String) : AiTurnSnackbar
    data object Declined : AiTurnSnackbar
}
