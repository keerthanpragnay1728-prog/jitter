package dev.molasses.data.repo

import dev.molasses.core.model.EngineSnapshot
import dev.molasses.data.datastore.CycleStateStore
import dev.molasses.engine.EngineStore

/**
 * [EngineStore] backed by Proto DataStore.
 *
 * [openSessionPkgProvider] is a function rather than a value because the
 * engine's open session changes between writes and the store needs the value
 * as of the write, not as of construction.
 */
class DataStoreEngineStore(
    private val store: CycleStateStore,
    private val bootIdProvider: () -> Int,
    private val openSessionPkgProvider: () -> String?,
) : EngineStore {
    override suspend fun persist(snapshot: EngineSnapshot) {
        store.write(
            snapshot = snapshot,
            bootId = bootIdProvider(),
            openSessionPkg = openSessionPkgProvider(),
        )
    }
}
