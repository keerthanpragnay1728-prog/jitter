package dev.molasses.core.bit

/**
 * Bit's face, as a pure function of mood, reaction and elapsed time.
 *
 * `(mood, reaction, tickMs) -> BitFrame`. No Android, no timers, no animation
 * objects. The host drives it from whatever clock it has and renders the
 * returned glyph; every transition is therefore reproducible and testable at
 * an exact millisecond.
 *
 * ## One confirmation sequence, defined once
 * Every command that succeeds routes through [Reaction.Confirm], and every
 * command that fails routes through [Reaction.Failed]. Commands do not get
 * their own animations. Fourteen verbs with fourteen bespoke sequences would
 * be fourteen things to keep in step, and the user would learn none of them;
 * one shape they see after every success is a signal they read without
 * thinking.
 *
 * Pure; no Android imports. Unit-tested in `BitStateMachineTest`.
 */
object BitStateMachine {

    /** Derived from accumulated cycle time. See [moodFor]. */
    enum class Mood { IDLE, VIGILANT, ANNOYED, GLITCHED }

    /** A transient overlay on the mood, started by an event. */
    sealed interface Reaction {
        data object None : Reaction

        /** A command succeeded. Carries the ack line to render alongside. */
        data class Confirm(val ack: String) : Reaction

        /** A command did not parse or could not run. Carries the error line. */
        data class Failed(val message: String) : Reaction

        /** Bit was tapped once. */
        data object Poked : Reaction

        /** Tapped repeatedly. */
        data object Irritated : Reaction

        /** Five rapid taps: turns away and ignores input. */
        data object TurnedAway : Reaction

        /** Battery under the critical threshold. Not transient; outlasts a tick. */
        data object BatteryCritical : Reaction
    }

    /** What the host renders this tick. */
    data class BitFrame(
        val face: String,
        /** Line rendered beside the face, or null. */
        val line: String? = null,
        /** True while the reaction owns the face and input should be ignored. */
        val ignoresInput: Boolean = false,
        /** False once the reaction has run its course. */
        val reactionActive: Boolean = true,
    )

    // ------------------------------------------------------------------ faces

    const val NEUTRAL = "(o_o)"
    const val BLINK_HALF = "( -_- )"
    const val BLINK_NARROW = "( o_o )"
    const val HAPPY = "(^o^)"
    const val DRY = "(._.)"
    const val IRRITATED = "(-_-;)"
    const val TURNED_AWAY = "[===]"
    const val BATTERY_CRITICAL = "[ . . ]"
    const val WARDEN = "(>_<)"

    // ------------------------------------------------------------- durations

    /**
     * The shared confirmation sequence, about 900 ms end to end.
     * `(o_o)` to `(^o^)` and back.
     */
    const val CONFIRM_RISE_MS = 150L
    const val CONFIRM_HOLD_MS = 600L
    const val CONFIRM_FALL_MS = 150L
    const val CONFIRM_TOTAL_MS = CONFIRM_RISE_MS + CONFIRM_HOLD_MS + CONFIRM_FALL_MS

    /**
     * Failure holds longer than success. A confirmation is a nicety; an error
     * is the only place the user learns what the grammar actually is, and it
     * has to survive the moment of looking away from the keyboard.
     */
    const val FAILED_TOTAL_MS = 2_000L

    const val POKE_TOTAL_MS = 1_200L
    const val IRRITATED_TOTAL_MS = 1_200L
    const val TURNED_AWAY_TOTAL_MS = 3_000L

    /** Glitch frames never run faster than this. 12 fps, per the brief. */
    const val MIN_GLITCH_FRAME_MS = 1000L / 12

    // ---------------------------------------------------------------- blink

    const val BLINK_MIN_INTERVAL_MS = 3_000L
    const val BLINK_MAX_INTERVAL_MS = 7_000L
    const val BLINK_HALF_MS = 75L

    // ------------------------------------------------------------------- api

    /** Accumulated cycle time to mood. The boundaries match `TierPolicy`. */
    fun moodFor(accumulatedMs: Long): Mood = when {
        accumulatedMs < 7 * 60_000L -> Mood.IDLE
        accumulatedMs < 15 * 60_000L -> Mood.VIGILANT
        accumulatedMs < 20 * 60_000L -> Mood.ANNOYED
        else -> Mood.GLITCHED
    }

    /**
     * The frame to render.
     *
     * @param reactionAgeMs milliseconds since the reaction started. Ignored
     *   when [reaction] is [Reaction.None].
     * @param tickMs a monotonic clock, used only for the idle blink phase.
     */
    fun frame(
        mood: Mood,
        reaction: Reaction,
        reactionAgeMs: Long,
        tickMs: Long,
    ): BitFrame = when (reaction) {
        Reaction.None -> idleFrame(mood, tickMs)

        is Reaction.Confirm -> phased(
            ageMs = reactionAgeMs,
            total = CONFIRM_TOTAL_MS,
            line = reaction.ack,
            expired = { idleFrame(mood, tickMs).copy(line = null, reactionActive = false) },
        ) { age ->
            when {
                age < CONFIRM_RISE_MS -> NEUTRAL
                age < CONFIRM_RISE_MS + CONFIRM_HOLD_MS -> HAPPY
                else -> NEUTRAL
            }
        }

        is Reaction.Failed -> phased(
            ageMs = reactionAgeMs,
            total = FAILED_TOTAL_MS,
            line = reaction.message,
            expired = { idleFrame(mood, tickMs).copy(line = null, reactionActive = false) },
        ) { DRY }

        Reaction.Poked -> phased(
            ageMs = reactionAgeMs,
            total = POKE_TOTAL_MS,
            line = null,
            expired = { idleFrame(mood, tickMs).copy(reactionActive = false) },
        ) { HAPPY }

        Reaction.Irritated -> phased(
            ageMs = reactionAgeMs,
            total = IRRITATED_TOTAL_MS,
            line = null,
            expired = { idleFrame(mood, tickMs).copy(reactionActive = false) },
        ) { IRRITATED }

        Reaction.TurnedAway -> phased(
            ageMs = reactionAgeMs,
            total = TURNED_AWAY_TOTAL_MS,
            line = null,
            ignoresInput = true,
            expired = { idleFrame(mood, tickMs).copy(reactionActive = false) },
        ) { TURNED_AWAY }

        // Not transient: it lasts as long as the battery does. The face
        // carries it, and the colour deliberately does not change. A red
        // battery reading in a green terminal is the one hue break this app
        // reserves for the terminal tier.
        Reaction.BatteryCritical -> BitFrame(face = BATTERY_CRITICAL, line = BAT_CRIT)
    }

    const val BAT_CRIT = "BAT:CRIT"

    /**
     * Whether a reaction has run its course, so the host can drop it without
     * rendering a frame first.
     */
    fun isExpired(reaction: Reaction, reactionAgeMs: Long): Boolean = when (reaction) {
        Reaction.None, Reaction.BatteryCritical -> false
        is Reaction.Confirm -> reactionAgeMs >= CONFIRM_TOTAL_MS
        is Reaction.Failed -> reactionAgeMs >= FAILED_TOTAL_MS
        Reaction.Poked -> reactionAgeMs >= POKE_TOTAL_MS
        Reaction.Irritated -> reactionAgeMs >= IRRITATED_TOTAL_MS
        Reaction.TurnedAway -> reactionAgeMs >= TURNED_AWAY_TOTAL_MS
    }

    // -------------------------------------------------------------- internal

    private inline fun phased(
        ageMs: Long,
        total: Long,
        line: String?,
        ignoresInput: Boolean = false,
        expired: () -> BitFrame,
        face: (Long) -> String,
    ): BitFrame {
        if (ageMs < 0 || ageMs >= total) return expired()
        return BitFrame(face(ageMs), line, ignoresInput, reactionActive = true)
    }

    /**
     * The resting face, including the blink and the glitch.
     *
     * The blink interval is deterministically pseudo-random in the 3 to 7
     * second band, derived from the tick rather than from a random source, so
     * it is testable and so it does not settle into the even rhythm that reads
     * as a loading spinner.
     */
    private fun idleFrame(mood: Mood, tickMs: Long): BitFrame {
        if (mood == Mood.GLITCHED) {
            // Quantised to the floor so a fast host cannot drive this above
            // 12 fps, which is where it stops reading as a glitch and starts
            // reading as a flicker.
            val phase = Math.floorDiv(tickMs, MIN_GLITCH_FRAME_MS)
            val face = if (phase % 2L == 0L) WARDEN else NEUTRAL
            return BitFrame(face)
        }

        val face = if (blinkPhaseMs(tickMs) < BLINK_HALF_MS) BLINK_HALF else NEUTRAL
        return BitFrame(face)
    }

    /**
     * Milliseconds into the current blink cycle.
     *
     * The interval varies per cycle inside the band, so successive blinks are
     * not evenly spaced.
     */
    internal fun blinkPhaseMs(tickMs: Long): Long {
        var cycleStart = 0L
        var index = 0L
        val safeTick = if (tickMs < 0) 0L else tickMs
        while (true) {
            val interval = blinkIntervalMs(index)
            if (safeTick < cycleStart + interval) return safeTick - cycleStart
            cycleStart += interval
            index += 1
            // A guard rather than a possibility: the interval is always at
            // least BLINK_MIN_INTERVAL_MS, so this terminates, but an
            // unbounded loop over a Long is not something to leave unguarded.
            if (index > MAX_BLINK_CYCLES) return 0L
        }
    }

    /** Deterministic interval for blink [index], inside the documented band. */
    internal fun blinkIntervalMs(index: Long): Long {
        // A small integer hash. Deterministic, no allocation, and spread
        // enough that the sequence does not read as a pattern.
        val mixed = (index * 2_654_435_761L) and 0x7FFF_FFFFL
        val span = BLINK_MAX_INTERVAL_MS - BLINK_MIN_INTERVAL_MS
        return BLINK_MIN_INTERVAL_MS + (mixed % (span + 1))
    }

    private const val MAX_BLINK_CYCLES = 1_000_000L
}
