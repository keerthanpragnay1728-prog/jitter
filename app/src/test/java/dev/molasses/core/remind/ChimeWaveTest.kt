package dev.molasses.core.remind

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ChimeWaveTest {

    private val pcm = ChimeWave.samples()
    private val lead = ChimeWave.LEAD_MS * ChimeWave.SAMPLE_RATE / 1000

    @Test
    fun `the buffer is the stated length`() {
        assertEquals(ChimeWave.TOTAL_MS * ChimeWave.SAMPLE_RATE / 1000, pcm.size)
        assertEquals(400, ChimeWave.TOTAL_MS)
    }

    @Test
    fun `the lead is silent, for the output to wake into`() {
        assertTrue((0 until lead).all { pcm[it] == 0.toShort() })
    }

    @Test
    fun `soft, never near clipping, and actually audible`() {
        val peak = ChimeWave.peak(pcm)
        assertTrue(peak <= (ChimeWave.AMPLITUDE * Short.MAX_VALUE).toInt() + 1)
        assertTrue("not silence", peak > Short.MAX_VALUE / 5)
    }

    @Test
    fun `it starts and ends at zero, so there is no click`() {
        assertTrue(abs(pcm[lead].toInt()) < 50)
        assertTrue(abs(pcm.last().toInt()) < 50)
        assertEquals(0.0, ChimeWave.envelope(0.0), 0.0)
        assertEquals(0.0, ChimeWave.envelope(ChimeWave.TONE_MS.toDouble()), 0.0)
    }

    @Test
    fun `the loudest part is near the start, a chime and not a swell`() {
        val tone = pcm.copyOfRange(lead, pcm.size)
        val loudest = tone.indices.maxBy { abs(tone[it].toInt()) }
        assertTrue(loudest * 1000 / ChimeWave.SAMPLE_RATE < 60)
    }
}
