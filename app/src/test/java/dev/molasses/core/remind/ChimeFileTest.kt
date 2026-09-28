package dev.molasses.core.remind

import dev.molasses.core.repoFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.log10

/**
 * The committed chime is what tools/gen-chime.py says it is.
 */
class ChimeFileTest {

    private val bytes by lazy { repoFile("app/src/main/res/raw/jitter_chime.wav").readBytes() }
    private fun le() = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    @Test
    fun `a mono 16-bit 44100 Hz PCM WAV`() {
        assertEquals("RIFF", String(bytes, 0, 4))
        assertEquals("WAVE", String(bytes, 8, 4))
        assertEquals("fmt ", String(bytes, 12, 4))
        val b = le()
        assertEquals(1, b.getShort(20).toInt())
        assertEquals(1, b.getShort(22).toInt())
        assertEquals(44_100, b.getInt(24))
        assertEquals(16, b.getShort(34).toInt())
    }

    @Test
    fun `about 280 ms, peaking at -12 dBFS, silent at both ends`() {
        assertEquals("data", String(bytes, 36, 4))
        val b = le()
        val n = b.getInt(40) / 2
        assertEquals(12_348, n)
        val pcm = IntArray(n) { b.getShort(44 + it * 2).toInt() }
        val peakDb = 20 * log10(pcm.maxOf { abs(it) } / 32_767.0)
        assertTrue("peak $peakDb dBFS", abs(peakDb + 12.0) < 0.1)
        assertTrue("short fade in", abs(pcm.first()) < 50)
        assertTrue("short fade out", abs(pcm.last()) < 50)
    }

    @Test
    fun `the script that made it is committed beside it and states the same parameters`() {
        val script = repoFile("tools/gen-chime.py").readText()
        for (p in listOf("RATE = 44100", "DURATION_MS = 280", "PEAK_DBFS = -12.0", "setnchannels(1)", "setsampwidth(2)")) {
            assertTrue(p, script.contains(p))
        }
    }
}
