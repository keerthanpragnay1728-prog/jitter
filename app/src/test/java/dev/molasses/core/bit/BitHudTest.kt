package dev.molasses.core.bit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BitHudTest {

    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    // -------------------------------------------------------------- steps

    @Test
    fun `a tap steps through two readouts and back to idle`() {
        assertEquals(HudStep.PRIMARY, BitHud.next(HudStep.NONE))
        assertEquals(HudStep.SECONDARY, BitHud.next(HudStep.PRIMARY))
        assertEquals(HudStep.NONE, BitHud.next(HudStep.SECONDARY))
    }

    @Test
    fun `stepping is a cycle, so a fourth tap starts again`() {
        var step = HudStep.NONE
        repeat(3) { step = BitHud.next(step) }
        assertEquals(HudStep.NONE, step)
        assertEquals(HudStep.PRIMARY, BitHud.next(step))
    }

    @Test
    fun `it times out after five seconds`() {
        assertFalse(BitHud.isExpired(0))
        assertFalse(BitHud.isExpired(BitHud.TIMEOUT_MS - 1))
        assertTrue(BitHud.isExpired(BitHud.TIMEOUT_MS))
    }

    @Test
    fun `a negative age reads as expired rather than as a fresh readout`() {
        assertTrue(BitHud.isExpired(-1))
    }

    // ------------------------------------------------------------ content

    @Test
    fun `step one is time until the cycle resets`() {
        assertEquals("[4h12m]", BitHud.textFor(HudStep.PRIMARY, 4 * hour + 12 * minute, 0).trim())
    }

    @Test
    fun `step two is cumulative time in the cycle`() {
        assertEquals("[38m]", BitHud.textFor(HudStep.SECONDARY, 0, 38 * minute).trim())
    }

    @Test
    fun `step none shows nothing, but still occupies the slot`() {
        val text = BitHud.textFor(HudStep.NONE, hour, hour)
        assertEquals("", text.trim())
        assertEquals(BitGlyph.WIDTH, text.length)
    }

    @Test
    fun `during a curfew step one is when it lifts, not the reset timer`() {
        // The same gesture and the same slot, with state-dependent content.
        // A second interaction for the curfew would have been the whole
        // gesture budget spent twice.
        val text = BitHud.textFor(
            step = HudStep.PRIMARY,
            cycleRemainingMs = 4 * hour,
            cumulativeMs = 0,
            curfewEndMinuteOfDay = 6 * 60,
        )
        assertEquals("[06:00]", text.trim())
    }

    @Test
    fun `a curfew does not change step two`() {
        val text = BitHud.textFor(HudStep.SECONDARY, 0, 38 * minute, curfewEndMinuteOfDay = 6 * 60)
        assertEquals("[38m]", text.trim())
    }

    @Test
    fun `the clock is always five characters`() {
        for (m in 0 until 24 * 60) assertEquals(5, BitHud.clock(m).length)
        assertEquals("00:00", BitHud.clock(0))
        assertEquals("06:00", BitHud.clock(6 * 60))
        assertEquals("23:59", BitHud.clock(23 * 60 + 59))
    }

    @Test
    fun `an out of range minute wraps rather than throwing`() {
        assertEquals("01:00", BitHud.clock(25 * 60))
        assertEquals("23:00", BitHud.clock(-60))
    }

    // ------------------------------------------------------ the fixed slot

    @Test
    fun `every readout fits the slot exactly, across the whole input range`() {
        // The guarantee that the HUD cannot move the layout. A string one
        // character wider than the widest face would change Bit's measured
        // width, which changes its snap target, which moves it while nobody
        // touched it.
        val durations = buildList {
            addAll(0L..5_000L step 137L)
            addAll(0L..(6 * hour) step minute)
            addAll(listOf(hour - 1, day, day + hour + 59 * minute, 99 * day, Long.MAX_VALUE / 4))
        }
        for (step in HudStep.entries) {
            for (d in durations) {
                for (curfew in listOf(null, 0, 6 * 60, 23 * 60 + 59)) {
                    val text = BitHud.textFor(step, d, d, curfew)
                    assertEquals("step=$step ms=$d curfew=$curfew -> '$text'", BitGlyph.WIDTH, text.length)
                }
            }
        }
    }

    @Test
    fun `a long duration drops its tail rather than truncating a number`() {
        // CommandRender renders every component that divides exactly, so 25h59m
        // is "1d1h59m", which does not fit. Truncating that mid-number would
        // read as a smaller value, which is worse than showing less.
        assertEquals("1d1h", BitHud.compact(day + hour + 59 * minute))
        assertEquals("5h59m", BitHud.compact(5 * hour + 59 * minute))
        assertEquals("38m", BitHud.compact(38 * minute))
    }

    @Test
    fun `compact never exceeds the field width`() {
        val samples = (0L..(400 * day) step (7 * minute + 13_000L)).toList()
        for (ms in samples) {
            assertTrue("$ms -> ${BitHud.compact(ms)}", BitHud.compact(ms).length <= BitHud.FIELD_WIDTH)
        }
    }

    @Test
    fun `zero and negative durations read as zero, not as empty`() {
        assertEquals("0m", BitHud.compact(0))
        assertEquals("0m", BitHud.compact(-1))
        assertEquals("[0m]", BitHud.textFor(HudStep.SECONDARY, 0, 0).trim())
    }

    @Test
    fun `the field width leaves room for the brackets`() {
        assertEquals(BitGlyph.WIDTH - 2, BitHud.FIELD_WIDTH)
    }
}
