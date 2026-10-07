package dev.molasses.core.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenTimeDriftTest {

    private val minute = 60_000L

    @Test
    fun `the system total leaves out our own package`() {
        val total = ScreenTimeDrift.systemTotal(
            mapOf("org.jitteros.app" to 90 * minute, "com.whatsapp" to 40 * minute, "com.chess" to 30 * minute),
            exclude = setOf("org.jitteros.app"),
        )
        assertEquals(70 * minute, total)
    }

    @Test
    fun `a negative entry counts as nothing`() {
        assertEquals(minute, ScreenTimeDrift.systemTotal(mapOf("a" to minute, "b" to -5 * minute), emptySet()))
    }

    @Test
    fun `the gap and the share read the device's day`() {
        // 1 h 56 min against 3 h 40 min.
        val r = ScreenTimeDrift.Reading(oursMs = 116 * minute, systemMs = 220 * minute)
        assertEquals(104 * minute, r.gapMs)
        assertEquals(52, r.oursPercentOfSystem)
    }

    @Test
    fun `no system time has no share`() {
        assertNull(ScreenTimeDrift.Reading(oursMs = minute, systemMs = 0L).oursPercentOfSystem)
        assertEquals(-minute, ScreenTimeDrift.Reading(oursMs = minute, systemMs = 0L).gapMs)
    }
}
