package dev.molasses.core.friction

import dev.molasses.core.friction.HorizonPolicy.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HorizonPolicyTest {

    private val min = 60_000L
    private val default = FrictionCurve.DEFAULT_HORIZON_MS

    // ------------------------------------------------------------- steps

    @Test
    fun `the offered steps ascend and span the allowed range`() {
        assertEquals(HorizonPolicy.STEPS_MS.sorted(), HorizonPolicy.STEPS_MS)
        assertEquals(HorizonPolicy.STEPS_MS.distinct(), HorizonPolicy.STEPS_MS)
        assertEquals(FrictionCurve.MIN_HORIZON_MS, HorizonPolicy.STEPS_MS.first())
        assertEquals(FrictionCurve.MAX_HORIZON_MS, HorizonPolicy.STEPS_MS.last())
    }

    @Test
    fun `the default is one of the offered steps`() {
        // Or the settings screen would open showing a value its own buttons
        // could never return to.
        assertTrue(default in HorizonPolicy.STEPS_MS)
    }

    @Test
    fun `snap resolves anything to a real step`() {
        assertEquals(10 * min, HorizonPolicy.snap(0))
        assertEquals(10 * min, HorizonPolicy.snap(-1))
        assertEquals(60 * min, HorizonPolicy.snap(Long.MAX_VALUE))
        assertEquals(18 * min, HorizonPolicy.snap(17 * min))
        assertEquals(20 * min, HorizonPolicy.snap(21 * min))
        for (step in HorizonPolicy.STEPS_MS) assertEquals(step, HorizonPolicy.snap(step))
    }

    @Test
    fun `wider and narrower walk the steps and stop at the ends`() {
        assertEquals(20 * min, HorizonPolicy.wider(18 * min))
        assertEquals(15 * min, HorizonPolicy.narrower(18 * min))
        assertEquals(60 * min, HorizonPolicy.wider(60 * min))
        assertEquals(10 * min, HorizonPolicy.narrower(10 * min))
        // A full walk up and back lands where it started.
        var v = HorizonPolicy.STEPS_MS.first()
        repeat(HorizonPolicy.STEPS_MS.size) { v = HorizonPolicy.wider(v) }
        assertEquals(HorizonPolicy.STEPS_MS.last(), v)
        repeat(HorizonPolicy.STEPS_MS.size) { v = HorizonPolicy.narrower(v) }
        assertEquals(HorizonPolicy.STEPS_MS.first(), v)
    }

    // ---------------------------------------------------------- widening

    @Test
    fun `widening waits for the rollover`() {
        val after = HorizonPolicy.request(State(18 * min), 60 * min)
        assertEquals("in force now", 18 * min, after.horizonMs)
        assertEquals("waiting", 60 * min, after.pendingHorizonMs)
    }

    @Test
    fun `a promoted widen is in force and nothing is left waiting`() {
        val promoted = HorizonPolicy.promote(State(18 * min, 60 * min))
        assertEquals(60 * min, promoted.horizonMs)
        assertFalse(promoted.hasPending)
    }

    @Test
    fun `a newer widen replaces an older one rather than queueing`() {
        val first = HorizonPolicy.request(State(18 * min), 60 * min)
        val second = HorizonPolicy.request(first, 30 * min)
        assertEquals(18 * min, second.horizonMs)
        assertEquals(30 * min, second.pendingHorizonMs)
    }

    // --------------------------------------------------------- narrowing

    @Test
    fun `narrowing applies immediately`() {
        // Nobody tightening their own leash should wait six hours for it, and
        // a narrower horizon cannot be an escape from anything.
        val after = HorizonPolicy.request(State(30 * min), 15 * min)
        assertEquals(15 * min, after.horizonMs)
        assertFalse(after.hasPending)
    }

    @Test
    fun `narrowing cancels a pending widen`() {
        val after = HorizonPolicy.request(State(18 * min, 60 * min), 15 * min)
        assertEquals(15 * min, after.horizonMs)
        assertFalse(after.hasPending)
    }

    @Test
    fun `asking again for the current horizon cancels a pending widen`() {
        // The only way to take one back, and it has to exist: a delay with no
        // cancel is a trap rather than a pause.
        val after = HorizonPolicy.request(State(18 * min, 60 * min), 18 * min)
        assertEquals(18 * min, after.horizonMs)
        assertFalse(after.hasPending)
    }

    // -------------------------------------------------------- idempotence

    @Test
    fun `applying the same request twice changes nothing the second time`() {
        // What lets the stored request be a standing preference rather than a
        // one-shot command something has to remember it consumed.
        for (requested in HorizonPolicy.STEPS_MS) {
            for (start in listOf(State(18 * min), State(18 * min, 60 * min), State(40 * min))) {
                val once = HorizonPolicy.request(start, requested)
                val twice = HorizonPolicy.request(once, requested)
                assertEquals("requested $requested from $start", once, twice)
            }
        }
    }

    @Test
    fun `promoting twice is the same as promoting once`() {
        val once = HorizonPolicy.promote(State(18 * min, 60 * min))
        assertEquals(once, HorizonPolicy.promote(once))
    }

    @Test
    fun `a request survives a rollover it did not reach`() {
        // Widen, then the process dies, then the cycle turns. The pending
        // value is state, not an in-memory intention.
        val requested = HorizonPolicy.request(State(18 * min), 60 * min)
        val restored = HorizonPolicy.of(requested.horizonMs, requested.pendingHorizonMs)
        assertEquals(requested, restored)
        assertEquals(60 * min, HorizonPolicy.promote(restored).horizonMs)
    }

    // ------------------------------------------------------------ stored

    @Test
    fun `an unset horizon reads as the default, not as zero clamped up`() {
        // Every existing install on the first launch after this ships.
        assertEquals(State(default, HorizonPolicy.NONE), HorizonPolicy.of(0, 0))
        assertEquals(default, HorizonPolicy.of(0, 0).horizonMs)
    }

    @Test
    fun `an unset pending is no pending`() {
        assertFalse(HorizonPolicy.of(30 * min, 0).hasPending)
        assertFalse(HorizonPolicy.of(30 * min, -1).hasPending)
    }

    @Test
    fun `a stored value off the ladder snaps rather than being rejected`() {
        assertEquals(20 * min, HorizonPolicy.of(21 * min, 0).horizonMs)
        assertEquals(60 * min, HorizonPolicy.of(18 * min, 99 * min).pendingHorizonMs)
    }

    // ------------------------------------------------- the proto keeps up

    @Test
    fun `the proto declares both halves of the horizon state`() {
        val appState = message("message AppState")
        assertTrue(
            "AppState has no horizon_ms",
            Regex("""\bhorizon_ms\s*=\s*6;""").containsMatchIn(appState),
        )
        assertTrue(
            "AppState has no pending_horizon_ms",
            Regex("""\bpending_horizon_ms\s*=\s*7;""").containsMatchIn(appState),
        )
        // New numbers, not reused. Field 4 has already changed meaning once
        // and a second reinterpretation of a live number is how a stored file
        // starts lying about itself.
        assertFalse(Regex("""\bhorizon_ms\s*=\s*[1-5];""").containsMatchIn(appState))
    }

    @Test
    fun `the proto stores the standing preference separately from what is in force`() {
        // Between a widen and the rollover that promotes it, the two differ.
        // One field could not hold both.
        assertTrue(
            "no app_horizon_ms map on CycleState",
            Regex("""map<string,\s*int64>\s+app_horizon_ms\s*=\s*\d+;""")
                .containsMatchIn(proto),
        )
    }

    private val proto: String by lazy {
        repoFile("app/src/main/proto/cycle_state.proto").readText()
    }

    private fun message(header: String): String {
        val start = proto.indexOf("$header {")
        check(start >= 0) { "no '$header' in cycle_state.proto" }
        return proto.substring(start, proto.indexOf("\n}", start))
    }

    private fun repoFile(relative: String): java.io.File {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir")!!).absoluteFile
        while (dir != null) {
            val candidate = java.io.File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("could not find $relative above ${System.getProperty("user.dir")}")
    }

    @Test
    fun `every state a request can produce is a real step`() {
        for (start in HorizonPolicy.STEPS_MS) {
            for (requested in HorizonPolicy.STEPS_MS) {
                val after = HorizonPolicy.request(State(start), requested)
                assertTrue(after.horizonMs in HorizonPolicy.STEPS_MS)
                assertTrue(!after.hasPending || after.pendingHorizonMs in HorizonPolicy.STEPS_MS)
                // And whichever way it went, the promoted end state is what
                // was asked for.
                assertEquals(requested, HorizonPolicy.promote(after).horizonMs)
            }
        }
    }
}
