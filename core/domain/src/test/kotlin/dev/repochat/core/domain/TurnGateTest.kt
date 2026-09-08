package dev.repochat.core.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for BUG-101: the single-slot turn scheduler must never
 * silently swallow a message sent while a FOREIGN conversation's turn runs.
 */
class TurnGateTest {

    @Test
    fun `idle coordinator accepts any turn`() {
        assertTrue(TurnGate.canStartTurn(currentRepoKey = "a/one", liveRepoKey = "", liveActive = false))
    }

    @Test
    fun `same-conversation active turn is allowed to be judged by the chat UI`() {
        // The chat's own typing/approval state blocks re-sends; the gate
        // itself only rejects *foreign* busy turns.
        assertTrue(TurnGate.canStartTurn(currentRepoKey = "a/one", liveRepoKey = "a/one", liveActive = true))
    }

    @Test
    fun `foreign active turn blocks the send`() {
        assertFalse(TurnGate.canStartTurn(currentRepoKey = "b/two", liveRepoKey = "a/one", liveActive = true))
    }

    @Test
    fun `blank live repo key with active flag is treated as same slot`() {
        // Defensive: an active turn without a repoKey must not block everything
        // forever (it also cannot be "foreign").
        assertTrue(TurnGate.canStartTurn(currentRepoKey = "b/two", liveRepoKey = "", liveActive = true))
    }
}
