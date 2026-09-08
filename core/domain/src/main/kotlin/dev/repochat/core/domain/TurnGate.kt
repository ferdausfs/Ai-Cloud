package dev.repochat.core.domain

/**
 * Pure decision logic for the single-slot turn scheduler (audit BUG-101).
 *
 * [AiTurnCoordinator][dev.repochat.turn.AiTurnCoordinator] runs at most one AI
 * turn at a time, app-wide. Before a new send is accepted, the UI must check
 * whether the busy slot belongs to *this* conversation or a *foreign* one.
 * Foreign busy -> block the send and tell the user (never silently drop the
 * message). Same-conversation busy is already guarded by the chat's own
 * typing/approval state.
 */
object TurnGate {

    /**
     * @param currentRepoKey conversation the user is trying to send from.
     * @param liveRepoKey    repoKey of the currently running turn ("" when idle).
     * @param liveActive     whether a turn is running at all.
     * @return true when a new turn may start for [currentRepoKey].
     */
    fun canStartTurn(
        currentRepoKey: String,
        liveRepoKey: String,
        liveActive: Boolean,
    ): Boolean {
        if (!liveActive) return true
        if (liveRepoKey.isBlank()) return true
        return liveRepoKey == currentRepoKey
    }
}
