package dev.molasses.core.lock

import dev.molasses.core.functionBody
import dev.molasses.core.repoFile
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The prefix write goes through [PrefixLock] inside the store's own
 * transform, the way `toggleTarget` goes through `TargetLock`. Read as text
 * because the DataStore layer is compiled by nothing here.
 */
class PrefixLockWiringTest {

    @Test
    fun `setSensitivePrefixes reads the locks and applies PrefixLock inside updateData`() {
        val text = repoFile("app/src/main/java/dev/molasses/data/datastore/CycleStateStore.kt").readText()
        val body = functionBody(text, "suspend fun setSensitivePrefixes(")
        val update = body.indexOf("store.updateData")
        val locks = body.indexOf("state.locksList")
        val guard = body.indexOf("PrefixLock.admitted(")
        val write = body.indexOf("addAllSensitivePackagePrefixes(admitted.prefixes)")
        assertTrue("the guard must run inside updateData, reading locks from the same state", update in 0 until locks)
        assertTrue("PrefixLock must decide before the write", locks < guard && guard < write)
    }
}
