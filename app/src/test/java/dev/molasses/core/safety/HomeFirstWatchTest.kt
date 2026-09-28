package dev.molasses.core.safety

import dev.molasses.core.safety.HomeFirstWatch.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeFirstWatchTest {

    private val ig = "com.instagram.android"

    private fun on(active: String, sensitive: Boolean = false, now: Long = 10_000L, last: Long? = null) =
        HomeFirstWatch.onForeground(active, gatedPkg = ig, sensitive = sensitive, nowMs = now, lastRehomeMs = last)

    @Test
    fun `the gated app coming back sends it home`() {
        assertEquals(Action.Rehome, on(ig))
    }

    @Test
    fun `another app coming to the front changes nothing`() {
        assertEquals(Action.Nothing, on("com.google.android.apps.maps"))
        assertEquals(Action.Nothing, on("dev.molasses"))
    }

    @Test
    fun `a sensitive app tears everything down, whatever else is true`() {
        assertEquals(Action.TearDown, on("com.phonepe.app", sensitive = true))
        assertEquals(Action.TearDown, on(ig, sensitive = true, last = 9_999L))
    }

    @Test
    fun `the debounce holds for two seconds and then lets one through`() {
        assertEquals(Action.Debounced, on(ig, now = 10_000L, last = 9_000L))
        assertEquals(Action.Debounced, on(ig, now = 10_999L, last = 9_000L))
        assertEquals(Action.Rehome, on(ig, now = 11_000L, last = 9_000L))
    }

    @Test
    fun `nothing gated, nothing to re-home`() {
        assertEquals(Action.Nothing, HomeFirstWatch.onForeground(ig, gatedPkg = null, sensitive = false, nowMs = 0L, lastRehomeMs = null))
    }
}
