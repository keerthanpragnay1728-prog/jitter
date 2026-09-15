package dev.molasses.core.bit

/**
 * The two numbers Jitter creates and does not answer.
 *
 * "How long until this resets" and "how deep am I" are both two taps away in
 * the ledger, which is two taps too many for questions the app itself puts in
 * the user's head. Docked Bit answers them in the slot it is already
 * occupying.
 *
 * ## Why it steps on a tap and never on a timer
 * A rotation that advances by itself means a user who glances up mid-rotation
 * reads a number with no label and no way to know which one it is. Stepping on
 * a tap means every reading was asked for, so the user always knows what they
 * are looking at.
 *
 * ## Why this is the only Bit interaction
 * Section 05 caps what Bit may do unprompted and bans anything that turns an
 * ambient presence into something that wants attention. The budget for
 * gestures is one. This spends it, and the curfew readout folds into the same
 * gesture rather than asking for a second.
 *
 * Pure; no Android imports. Unit-tested in `BitHudTest`.
 */
object BitHud {

    /**
     * Back to idle after five seconds with no further tap.
     *
     * Derived from the existing 40 ms tick by subtraction rather than by a
     * timer of its own, the same way the prompt placeholder rotates. Bit
     * already recomposes at that rate; a second ticker would buy nothing.
     */
    const val TIMEOUT_MS = 5_000L

    /** Longest number the slot can hold, once the brackets are accounted for. */
    val FIELD_WIDTH: Int = BitGlyph.WIDTH - 2

    /** NONE to PRIMARY to SECONDARY to NONE. A tap advances, three taps close. */
    fun next(step: HudStep): HudStep = when (step) {
        HudStep.NONE -> HudStep.PRIMARY
        HudStep.PRIMARY -> HudStep.SECONDARY
        HudStep.SECONDARY -> HudStep.NONE
    }

    fun isExpired(ageMs: Long): Boolean = ageMs < 0L || ageMs >= TIMEOUT_MS

    /**
     * The readout for [step], already padded into the fixed slot.
     *
     * @param curfewEndMinuteOfDay when a bedtime lock is standing, the minute
     *   of day it lifts. Null when there is no curfew.
     */
    fun textFor(
        step: HudStep,
        cycleRemainingMs: Long,
        cumulativeMs: Long,
        curfewEndMinuteOfDay: Int? = null,
    ): String = BitGlyph.pad(
        when (step) {
            HudStep.NONE -> ""
            // During a curfew the reset timer is not the question. When the
            // phone wakes up is.
            HudStep.PRIMARY ->
                if (curfewEndMinuteOfDay != null) bracket(clock(curfewEndMinuteOfDay))
                else bracket(compact(cycleRemainingMs))
            HudStep.SECONDARY -> bracket(compact(cumulativeMs))
        },
    )

    /**
     * A duration in at most [FIELD_WIDTH] characters.
     *
     * `CommandRender.duration` renders every component that divides exactly,
     * so a 25 hour 59 minute total comes back as `1d1h59m`, which does not
     * fit. Two components is the most the slot can hold and is also the most
     * anyone reads at a glance, so the tail is dropped rather than the string
     * being truncated mid-number into something that looks like a smaller
     * value.
     */
    fun compact(ms: Long): String {
        if (ms <= 0L) return "0m"
        val parts = componentsOf(ms)
        if (parts.isEmpty()) return "0m"
        val two = parts.take(2).joinToString("")
        if (two.length <= FIELD_WIDTH) return two
        val one = parts.first()
        return if (one.length <= FIELD_WIDTH) one else one.take(FIELD_WIDTH)
    }

    /** `HH:mm`, always five characters. */
    fun clock(minuteOfDay: Int): String {
        val m = ((minuteOfDay % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY
        return "${pad2(m / 60)}:${pad2(m % 60)}"
    }

    private fun bracket(body: String): String = "[$body]"

    private fun pad2(v: Int): String = if (v < 10) "0$v" else v.toString()

    /** Largest units first, zero components omitted. Mirrors `CommandRender`. */
    private fun componentsOf(ms: Long): List<String> {
        var rest = ms
        val out = mutableListOf<String>()
        for ((unit, suffix) in UNITS) {
            val n = rest / unit
            if (n > 0) {
                out += "$n$suffix"
                rest -= n * unit
            }
        }
        return out
    }

    private const val MINUTES_PER_DAY = 24 * 60

    private val UNITS: List<Pair<Long, String>> = listOf(
        24 * 60 * 60 * 1000L to "d",
        60 * 60 * 1000L to "h",
        60 * 1000L to "m",
        1000L to "s",
    )
}
