package dev.molasses.core.lock

import dev.molasses.core.repoFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bypass, and the three ways it must not come back.
 *
 * Unticking a locked app in CFG removed it from the target set, which removed
 * it from `packageNames`, which stopped the service receiving its events, which
 * stopped the lock being enforced. Two taps, from inside the app, past every
 * other control that exists to make a lock hard to undo.
 */
class TargetLockTest {

    private val ig = "com.instagram.android"
    private val yt = "com.google.android.youtube"
    private val hour = 60L * 60 * 1000

    @Test
    fun `a locked app cannot be untracked`() {
        assertNull(TargetLock.toggled(listOf(ig, yt), ig, lockRemainingMs = hour))
    }

    @Test
    fun `an unlocked app can be untracked`() {
        assertEquals(listOf(yt), TargetLock.toggled(listOf(ig, yt), ig, lockRemainingMs = 0L))
    }

    @Test
    fun `an expired lock does not hold the toggle`() {
        // remainingMs is recomputed from the clock on every read, so an
        // expired lock reads as zero and needs no separate notion of expiry
        // here. This says so rather than leaving it to the reader.
        assertEquals(listOf(yt), TargetLock.toggled(listOf(ig, yt), ig, lockRemainingMs = 0L))
    }

    @Test
    fun `tracking a locked app is still allowed`() {
        // The lock forbids one direction. Refusing both would strand anyone
        // who untracked an app before the lock was armed, and adding it back
        // is not a bypass of anything.
        assertEquals(listOf(yt, ig), TargetLock.toggled(listOf(yt), ig, lockRemainingMs = hour))
    }

    @Test
    fun `refusing is a null and not an unchanged list`() {
        // The caller has to tell "refused" from "toggled to the same value"
        // in order to say so on screen. An unchanged list would be silently
        // inert, which is how a locked toggle reads as a broken screen.
        val current = listOf(ig)
        assertNull(TargetLock.toggled(current, ig, hour))
        assertTrue(TargetLock.toggled(current, ig, 0L) != null)
    }

    @Test
    fun `an empty package is refused rather than appended`() {
        assertNull(TargetLock.toggled(listOf(ig), "", 0L))
    }

    @Test
    fun `the toggle is a real toggle when nothing is locked`() {
        // Off, on, off, and back to where it started. The read-modify-write
        // is inside this function precisely so this can be asserted.
        var list = listOf(ig, yt)
        list = TargetLock.toggled(list, ig, 0L)!!
        assertFalse(ig in list)
        list = TargetLock.toggled(list, ig, 0L)!!
        assertTrue(ig in list)
        assertEquals(setOf(ig, yt), list.toSet())
    }

    @Test
    fun `isPinned matches exactly what toggled refuses`() {
        // Two functions, one condition. If they ever disagree the control is
        // either enabled and refusing, or disabled and permitted, and both
        // read as a bug on the screen a user checks to see what is happening.
        val current = listOf(ig)
        for (pkg in listOf(ig, yt)) {
            for (remaining in listOf(0L, 1L, hour)) {
                val tracked = pkg in current
                val refused = TargetLock.toggled(current, pkg, remaining) == null
                assertEquals(
                    "pkg=$pkg tracked=$tracked remaining=$remaining",
                    TargetLock.isPinned(tracked, remaining),
                    refused,
                )
            }
        }
    }

    @Test
    fun `a lock armed by any verb holds it, because the source is not asked`() {
        // block, focus and bedtime all end in LockRegistry, and this reads a
        // remaining time rather than a reason. That is what makes "you cannot
        // untick your way out of a focus session" true without a second
        // guard for focus.
        for (reason in LockReason.entries) {
            val registry = LockRegistry().arm(
                pkg = ig,
                now = stamp(0),
                durationMs = hour,
                reason = reason,
            )
            val remaining = registry.remainingMs(ig, stamp(0))
            assertTrue("$reason should stand", remaining > 0L)
            assertNull("$reason must hold the toggle", TargetLock.toggled(listOf(ig), ig, remaining))
        }
    }

    // ------------------------------------------------------- the only write

    @Test
    fun `there is no way to write the target list that skips the guard`() {
        // The guard was complete because toggleTarget was the only caller of
        // setTargets. Complete-because-there-is-one-caller is a guard waiting
        // for the second caller, and this repository has paid for that twice.
        for (path in listOf(
            "app/src/main/java/dev/molasses/data/datastore/CycleStateStore.kt",
            "app/src/main/java/dev/molasses/data/repo/SettingsRepository.kt",
        )) {
            val text = repoFile(path).readText()
            assertFalse(
                "$path still declares setTargets; the target list must only " +
                    "be writable through toggleTarget",
                text.contains("fun setTargets("),
            )
        }
    }

    @Test
    fun `the store decides inside the transform, not against a snapshot`() {
        // The window this closes: the view model read a targets snapshot and
        // a locks snapshot and then wrote, so a lock armed in between was
        // missed, and bedtime arms on a timer with nobody watching.
        val text = repoFile(
            "app/src/main/java/dev/molasses/data/datastore/CycleStateStore.kt",
        ).readText()
        val start = text.indexOf("suspend fun toggleTarget(")
        assertTrue("toggleTarget has been renamed or removed", start >= 0)
        val end = text.indexOf("\n    /**", start)
        val body = text.substring(start, if (end > start) end else text.length)
        assertTrue("toggleTarget must write through updateData", body.contains("store.updateData"))
        assertTrue(
            "the lock must be read from the same state the write builds on",
            body.contains("state.locksList"),
        )
        assertTrue(
            "the decision must go through TargetLock",
            body.contains("TargetLock.toggled("),
        )
    }

    private fun stamp(elapsedMs: Long) = dev.molasses.core.time.StampedInstant(
        wallMs = 1_700_000_000_000L + elapsedMs,
        elapsedMs = elapsedMs,
        bootId = 3,
    )
}
