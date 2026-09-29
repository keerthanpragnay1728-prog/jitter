package dev.molasses.engine

import dev.molasses.core.model.EngineSnapshot
import dev.molasses.core.model.EventType

/**
 * Everything [FrictionEngine] needs from the outside world, as pure interfaces,
 * so the engine itself has no Android imports and no I/O. Both implementations
 * live in `data/repo` and both are asynchronous -- nothing here may block the
 * accessibility callback thread.
 */

/** Hot-state persistence (Proto DataStore in production). */
interface EngineStore {
    suspend fun persist(snapshot: EngineSnapshot)
}

/** Append-only ledger (Room in production). Fire-and-forget. */
interface FrictionLedger {
    fun log(pkg: String, type: EventType, meta: String? = null)
}
