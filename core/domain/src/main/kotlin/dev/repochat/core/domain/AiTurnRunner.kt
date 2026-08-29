package dev.repochat.core.domain

import dev.repochat.core.model.TurnEvent
import dev.repochat.core.model.TurnRequest
import kotlinx.coroutines.flow.Flow

/**
 * Runs one full AI turn: branch ensure, tree fetch, the model loop
 * (read_file -> context -> model -> ...), and — on write_file — suspends
 * waiting for an explicit user decision on [approval] before committing.
 */
interface AiTurnRunner {
    fun runTurn(request: TurnRequest, approval: Flow<Boolean>): Flow<TurnEvent>
}
