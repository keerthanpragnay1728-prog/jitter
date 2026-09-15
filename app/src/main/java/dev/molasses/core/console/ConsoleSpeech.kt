package dev.molasses.core.console

/**
 * When Bit is allowed to say the thing it has queued.
 *
 * ## The rule that makes the caps mean anything
 * **The budget counts what was rendered, never what was attempted.** Every
 * other outcome holds the line in the queue and spends nothing. A line
 * counted but never seen is the worst available outcome: it costs the user
 * one of three an hour and gives them nothing, and it is invisible in any log
 * because from the outside it looks exactly like a line that was shown.
 *
 * That single rule covers every suppression path uniformly. A call, a
 * financial app, an open gate, a command acknowledgement in the way, a full
 * budget: all of them are "not rendered", so all of them hold and none of
 * them count.
 *
 * ## Where a NOTICE sits, and why I moved it
 * The brief placed NOTICE above `reaction` and offered the swap. It is below
 * it here.
 *
 * A reaction is the answer to something the user just did. A notice is
 * something Bit volunteered. When the two collide the user's own action wins,
 * which is the principle already holding up two other rows of that table
 * (reaction over armed, reaction over the slit) and the one that keeps Bit a
 * tool rather than something with opinions about you. The collision is not
 * rare either: a notice is delivered on returning home, and returning home is
 * exactly when someone types a command.
 *
 * The cap problem that motivated putting it higher is real, but it is a
 * property of *when you count*, not of where the line sits. Counting renders
 * fixes it outright, at any position.
 *
 * Pure; no Android imports. Unit-tested in `ConsoleSpeechTest`.
 */
object ConsoleSpeech {

    /** A notice is gone this long after it is first drawn. Never a prompt. */
    const val NOTICE_TIMEOUT_MS = 8_000L

    const val MAX_PER_HOUR = 3
    const val MAX_PER_DAY = 8

    const val HOUR_MS = 60L * 60 * 1000
    const val DAY_MS = 24L * HOUR_MS

    /**
     * What has been spent.
     *
     * ## Why the wall clock
     * These are rate limits on how often a person is spoken to, so they are
     * measured in the clock a person lives in. The usual objection does not
     * apply: the failure mode of a forward jump is Bit speaking *more*, and
     * nobody winds their clock to be nagged. `elapsedRealtime` would be
     * strictly worse, because it restarts at zero on every reboot and would
     * hand out a fresh budget with the power button.
     */
    data class Budget(
        /** Wall times of rendered lines, pruned to the day window. */
        val deliveredAtWallMs: List<Long> = emptyList(),
        /** Ids rendered in [cycleAnchorWallMs]'s cycle. Never the same twice. */
        val seenIds: Set<String> = emptySet(),
        /**
         * The cycle [seenIds] belongs to.
         *
         * Carried rather than cleared by a rollover hook, so the set expires
         * itself: a different anchor is a different cycle and the ids go with
         * it. Nothing has to remember to reset anything.
         */
        val cycleAnchorWallMs: Long = 0L,
    )

    /** Conditions checked when the line would be drawn, never when queued. */
    data class Gate(
        val callInProgress: Boolean = false,
        val sensitiveForeground: Boolean = false,
        val gateActive: Boolean = false,
        /** Something above a notice in the precedence table owns the slot. */
        val outranked: Boolean = false,
    )

    /** Why nothing was said. For the log and for tests, never for the user. */
    enum class Hold {
        NOTHING_QUEUED,

        /**
         * Already said this cycle. Unlike every other hold this one can never
         * clear, so the caller discards the line rather than leaving it to
         * occupy the queue.
         */
        ALREADY_SEEN,

        CALL,
        SENSITIVE,
        GATE,
        OUTRANKED,
        HOURLY_CAP,
        DAILY_CAP,
    }

    sealed interface Verdict {
        /** Draw it, and persist [budget]. The only path that spends anything. */
        data class Render(val line: ConsoleLine, val budget: Budget) : Verdict

        data class Held(val reason: Hold) : Verdict
    }

    /**
     * @param cycleAnchorWallMs the current cycle's anchor, which decides
     *   whether [Budget.seenIds] still applies.
     */
    fun evaluate(
        queued: ConsoleLine?,
        budget: Budget,
        gate: Gate,
        cycleAnchorWallMs: Long,
        nowWallMs: Long,
    ): Verdict {
        if (queued == null) return Verdict.Held(Hold.NOTHING_QUEUED)

        // A different anchor is a different cycle, so the ids from the last
        // one do not apply.
        val sameCycle = budget.cycleAnchorWallMs == cycleAnchorWallMs
        val seen = if (sameCycle) budget.seenIds else emptySet()
        if (queued.id in seen) return Verdict.Held(Hold.ALREADY_SEEN)

        // Delivery-time suppression, in the order a person would rank the
        // reasons. None of these spends anything.
        if (gate.callInProgress) return Verdict.Held(Hold.CALL)
        if (gate.sensitiveForeground) return Verdict.Held(Hold.SENSITIVE)
        if (gate.gateActive) return Verdict.Held(Hold.GATE)
        if (gate.outranked) return Verdict.Held(Hold.OUTRANKED)

        val recent = budget.deliveredAtWallMs.filter { nowWallMs - it in 0 until DAY_MS }
        if (recent.count { nowWallMs - it < HOUR_MS } >= MAX_PER_HOUR) {
            return Verdict.Held(Hold.HOURLY_CAP)
        }
        if (recent.size >= MAX_PER_DAY) return Verdict.Held(Hold.DAILY_CAP)

        return Verdict.Render(
            line = queued,
            budget = Budget(
                deliveredAtWallMs = (recent + nowWallMs).takeLast(MAX_PER_DAY),
                seenIds = seen + queued.id,
                cycleAnchorWallMs = cycleAnchorWallMs,
            ),
        )
    }

    /**
     * Whether a delivered notice has run its eight seconds.
     *
     * Takes an age rather than a clock, so the host can derive it from the
     * tick it is already running rather than starting a timer. A prompt never
     * calls this: it has no lifetime to be expired by.
     */
    fun noticeExpired(ageMs: Long): Boolean = ageMs < 0L || ageMs >= NOTICE_TIMEOUT_MS
}
