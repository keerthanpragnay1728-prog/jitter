package dev.molasses.core.remind

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The reminder chime as 16-bit mono PCM, generated rather than bundled.
 * Pure, so its shape is tested rather than listened for.
 *
 * ## Why not ToneGenerator
 * On hardware the receiver ran and the text reached the console, and no
 * sound came out. The tone was `TONE_PROP_BEEP`, one beep of well under
 * 150 ms, started the instant the generator was created. When the audio
 * output has been idle, the path that wakes it can swallow the first part of
 * whatever is played, and a sound shorter than that wake-up is lost whole.
 * The double BEEP2 before it was long enough to survive. That is the most
 * likely cause and it is not confirmed; the chime logs the stream's volume,
 * mute and interruption filter so the next run can rule the others out.
 *
 * So the sound starts with [LEAD_MS] of silence, which the wake-up can eat
 * without eating the tone, and the tone itself is a plain sine with a soft
 * attack and a decay, which reads as a chime rather than a beep.
 *
 * A generated buffer rather than a bundled WAV because a binary resource
 * would be the one file in `app/src` the encoding check cannot read, and
 * this is short enough to state as arithmetic.
 */
object ChimeWave {

    const val SAMPLE_RATE = 44_100

    /** Silence first, for the output to wake into. */
    const val LEAD_MS = 120

    /** The audible part. */
    const val TONE_MS = 280

    const val TOTAL_MS = LEAD_MS + TONE_MS

    /** E5. Low enough to be soft, high enough to carry from a pocket. */
    const val FREQ_HZ = 659.25

    /** Peak, as a fraction of full scale. Well short of clipping, and soft. */
    const val AMPLITUDE = 0.35

    private const val ATTACK_MS = 12.0
    private const val DECAY_TAU_MS = 90.0
    private const val RELEASE_MS = 25.0

    fun samples(): ShortArray {
        val lead = LEAD_MS * SAMPLE_RATE / 1000
        val tone = TONE_MS * SAMPLE_RATE / 1000
        val out = ShortArray(lead + tone)
        for (i in 0 until tone) {
            val tMs = i * 1000.0 / SAMPLE_RATE
            val env = envelope(tMs)
            val v = AMPLITUDE * env * sin(2 * PI * FREQ_HZ * i / SAMPLE_RATE)
            out[lead + i] = (v * Short.MAX_VALUE).roundToInt().toShort()
        }
        return out
    }

    /** 0 to 1: a raised-cosine attack, an exponential decay, a raised-cosine release to exactly zero. */
    fun envelope(tMs: Double): Double {
        if (tMs <= 0.0 || tMs >= TONE_MS) return 0.0
        val attack = if (tMs < ATTACK_MS) 0.5 - 0.5 * cos(PI * tMs / ATTACK_MS) else 1.0
        val decay = exp(-(tMs - ATTACK_MS).coerceAtLeast(0.0) / DECAY_TAU_MS)
        val toEnd = TONE_MS - tMs
        val release = if (toEnd < RELEASE_MS) 0.5 - 0.5 * cos(PI * toEnd / RELEASE_MS) else 1.0
        return attack * decay * release
    }

    /** Largest absolute sample, for the tests and the log. */
    fun peak(pcm: ShortArray): Int = pcm.maxOfOrNull { abs(it.toInt()) } ?: 0
}
