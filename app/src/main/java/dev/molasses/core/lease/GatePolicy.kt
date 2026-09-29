package dev.molasses.core.lease

/**
 * What the launch gate asks for, and what it offers.
 *
 * ## The lease gate replaces the checkpoint gate
 * The old design gated on a tier boundary: scroll far enough and a gate
 * appeared mid-session. The gate now fires on *entering* a target app, before
 * the first scroll, and the toll buys a fixed span of wall-clock time rather
 * than a fixed span of accumulated time. Two reasons.
 *
 * The moment worth interrupting is the launch. A gate that appears eleven
 * minutes into a session arrives after the decision it was meant to inform,
 * and the honest answer to "do you want to keep going" at that point is
 * always yes.
 *
 * And a checkpoint gate could be waited out. Leaving the app left
 * `gatePending` true and changed nothing else, so the user came back to the
 * same gate; but they also came back to the same app, which is what they
 * wanted. A lease has to be taken before the app opens at all, which is the
 * only point where the answer can still be no.
 *
 * Pure; no Android imports. Unit-tested in `GatePolicyTest`.
 */
object GatePolicy {

    /**
     * What clearing the gate costs.
     *
     * [COUNTDOWN] is the default and the only mode reachable without changing
     * a setting. It asks for nothing but the seconds: no puzzle, no typing, no
     * walking. The toll is attention, paid in full, while the numbers on the
     * screen say what the app has already cost today.
     *
     * The other two are the movement gate and its typing escape hatch, kept
     * because removing them would remove the escape hatch with them.
     */
    enum class GateMode {
        /** Wait out the seconds. See [GateCountdown]. */
        COUNTDOWN,

        /** Walk. The IMU gate, off by default, terminal tier only. */
        WALK,

        /**
         * Type a phrase instead of walking.
         *
         * This is an accessibility requirement, not a preference: a user who
         * cannot walk on demand must still be able to clear a gate they chose
         * to enable. It stays selectable for exactly that reason, which is
         * also why the setting is a three-way choice rather than a switch.
         * Collapsing it to on/off would have deleted this.
         */
        TYPING_ONLY,
    }

    /**
     * The mode actually in force.
     *
     * [WALK] and [TYPING_ONLY] apply at the terminal tier and nowhere else.
     * Below terminal the toll is always the countdown, whatever the setting
     * says: asking someone to walk laps to open an app they have used for four
     * minutes today is out of proportion, and a gate out of proportion to what
     * it is gating gets the whole app uninstalled.
     */
    fun modeFor(configured: GateMode, terminal: Boolean): GateMode =
        if (terminal) configured else GateMode.COUNTDOWN
}

/**
 * The lease durations on offer, and nothing else.
 *
 * Three buttons, no picker, no free entry. A field would turn the decision
 * into a negotiation with itself, and the useful question is not "how many
 * minutes exactly" but "a little, or a bit more".
 *
 * No zero and no unlimited. Zero is what the back button already is, and an
 * unlimited option is the option everybody picks.
 */
object LeaseLadder {

    val OFFERED_MS: List<Long> = listOf(
        5L * 60 * 1000,
        10L * 60 * 1000,
        15L * 60 * 1000,
    )

    val MIN_MS: Long get() = OFFERED_MS.first()
    val MAX_MS: Long get() = OFFERED_MS.last()

    /** True when a duration is one this app would actually hand out. */
    fun isOffered(durationMs: Long): Boolean = durationMs in OFFERED_MS
}

/**
 * How long the countdown runs, and why it gets longer.
 *
 * ## Escalation is the only lever
 * When a lease expires with the target still in front, the gate comes back.
 * If it came back identical, the cost of a whole afternoon would be a fixed
 * eight seconds per lease, and a user would learn to tap through it without
 * reading. So each gate within a cycle runs longer than the last.
 *
 * ## Why lengthen the wait rather than refuse
 * Refusing outright is what a lock is for, and a lock is something the user
 * chose. A gate that started refusing on its own would be making that choice
 * for them, from an overlay, with no way back. Lengthening stays honest: the
 * app is always still reachable, it just costs more of the thing the user is
 * actually spending, which is attention.
 *
 * ## The reset is the cycle, not the lease
 * Counting resets on cycle rollover and on nothing else. Resetting when a
 * lease expires would reset it every time it is used, which is not escalation
 * at all. This is the same shape as the penalty ratchet in the engine: it
 * climbs within a cycle and only a rollover clears it.
 *
 * Pure; no Android imports. Unit-tested in `GateCountdownTest`.
 */
object GateCountdown {

    /** First gate of a cycle. */
    const val FIRST_MS = 8_000L

    /** Added per gate already taken in this cycle. */
    const val STEP_MS = 4_000L

    /**
     * Ceiling. Past about half a minute the gate stops reading as a toll and
     * starts reading as the app being broken, and a broken app gets
     * uninstalled rather than obeyed.
     */
    const val CAP_MS = 30_000L

    /**
     * @param leasesTakenThisCycle how many leases this package has already
     *   been granted in the current cycle. Zero for the first gate.
     *
     * Counted on leases taken rather than on gates shown. A gate the user
     * backed out of cost them the app, not the app's time, and charging for
     * it would make leaving the most expensive answer available. Opening and
     * backing out repeatedly buys nothing either way: each attempt still costs
     * the full countdown.
     *
     * Gives 8s, 12s, 16s, 20s, 24s, 28s, then 30s from there on. A negative
     * count is treated as zero rather than trusted; it can only be a corrupt
     * read and the shorter countdown is the direction that would be exploited.
     */
    fun durationMs(leasesTakenThisCycle: Int): Long =
        (FIRST_MS + STEP_MS * leasesTakenThisCycle.coerceAtLeast(0)).coerceAtMost(CAP_MS)
}

/**
 * Whether the launch gate may attach at all.
 *
 * ## Why this is a type and not four lines in the service
 * Same reason as [dev.molasses.core.lock.LockEnforcement]: it encodes a
 * precedence order whose entries do not all point the same way, and an order
 * written as a function is an order that can be asserted.
 *
 * Pure; no Android imports. Unit-tested in `LaunchGateTest`.
 */
object LaunchGate {

    sealed interface Decision {
        /**
         * Intercept. [countdownMs] is how long before the decision panel
         * surfaces; [expired] marks the gate as a returning one, which the
         * overlay renders as LEASE EXPIRED rather than as a first launch.
         */
        data class Intercept(
            val countdownMs: Long,
            val mode: GatePolicy.GateMode,
            val expired: Boolean,
        ) : Decision

        /** Let it through. [why] is for the log, not for the user. */
        data class Pass(val why: String) : Decision
    }

    /**
     * What the caller should do once it has tried to put a gate up.
     *
     * ## Why a gate that did not draw is not a gate
     * [LaunchGate.decide] says a gate is owed. Whether one is actually on the
     * glass is a different question, and conflating them cost every stall on
     * a device where the window could not be added: the caller committed as
     * soon as it dispatched, so it returned before arming the shutter, and
     * every later scroll re-entered, re-failed and returned the same way. One
     * throw disabled the gate and all the friction behind it, permanently,
     * with a healthy service and a correct ledger.
     *
     * So the rule is stated as a type. A gate suppresses friction only while
     * it is genuinely covering the app. A gate that could not be drawn
     * suppresses nothing: the app is on screen and scrollable, so the stall
     * is the only friction left and it must still run.
     *
     * The direction that matters is that a failure costs the *gate*, never
     * the friction. An overlay that cannot be added is a device or a platform
     * problem; letting it also hand the user an unfrictioned app would turn
     * someone else's bug into a bypass.
     */
    sealed interface Outcome {
        /** On the glass. Nothing else of ours belongs behind it. */
        data object Shown : Outcome

        /**
         * Owed, dispatched, and not drawn. Fall through to ordinary friction
         * and say so loudly: this is never normal.
         */
        data class NotDrawn(val why: String) : Outcome

        /** None was owed. [Pass] said why. */
        data object NotOwed : Outcome

        /**
         * True only for [Shown]. The single question every caller asks, in
         * one place, so no call site can answer it by reading a boolean that
         * meant something slightly different.
         */
        val suppressesFriction: Boolean get() = this == Shown
    }

    /**
     * Fold a decision and what actually happened into one answer.
     *
     * @param attached what the overlay manager reports **after** the attempt,
     *   not what it was asked to do.
     */
    fun outcome(decision: Decision, attached: Boolean): Outcome = when {
        decision is Decision.Pass -> Outcome.NotOwed
        attached -> Outcome.Shown
        else -> Outcome.NotDrawn("gate window could not be added")
    }

    /**
     * @param sensitiveForeground the foreground package is in the
     *   never-draw-over set. Outranks everything: an overlay sets
     *   `FLAG_WINDOW_IS_OBSCURED` on that app's touches and a hardened payment
     *   app is entitled to refuse the transaction.
     * @param locked a lock is standing on this package. The lock flash and the
     *   home action own that case; two overlays for one launch would fight.
     * @param paused the user armed the fifteen minute pause. A pause suspends
     *   graduated friction, and the launch gate is graduated friction. Note
     *   this is the opposite answer from `LockEnforcement`, where a pause does
     *   not outrank a lock: a lock is a commitment the user made deliberately,
     *   a gate is a nudge they never agreed to individually.
     * @param leaseRemainingMs from [LeaseManager.remainingMs].
     * @param leasesTakenThisCycle drives the escalation, and also decides
     *   whether this gate is a returning one. See [GateCountdown].
     * @param inCall a call is in progress, by the audio mode. A precondition
     *   of attaching any gate, checked here so every mode goes through it.
     *   The lease gate also dismisses itself on its tick when a call starts
     *   after it is up; that check used to be the only one, so during a VoIP
     *   call the gate attached on every scroll, took audio focus, and came
     *   down 200 ms later. Passing records nothing, so the next entry or
     *   scroll after the call asks again and the gate is never skipped for
     *   the rest of the session. A false positive (some voice apps set
     *   `MODE_IN_COMMUNICATION`) costs a gate, which is the accepted
     *   direction: a gate over a call the user is trying to hear is worse.
     */
    fun decide(
        isTarget: Boolean,
        leaseRemainingMs: Long,
        sensitiveForeground: Boolean,
        locked: Boolean,
        paused: Boolean,
        leasesTakenThisCycle: Int,
        configuredMode: GatePolicy.GateMode,
        terminal: Boolean,
        inCall: Boolean,
    ): Decision = when {
        !isTarget -> Decision.Pass("not a target")
        sensitiveForeground -> Decision.Pass("sensitive package")
        inCall -> Decision.Pass("call in progress")
        locked -> Decision.Pass("locked, the lock flash owns this")
        paused -> Decision.Pass("paused")
        leaseRemainingMs > 0L -> Decision.Pass("lease active")
        else -> Decision.Intercept(
            countdownMs = GateCountdown.durationMs(leasesTakenThisCycle),
            mode = GatePolicy.modeFor(configuredMode, terminal),
            // A package that has been granted a lease this cycle and holds
            // none now is one whose lease ran out. Derived rather than
            // flagged, deliberately: a stored flag could disagree with the
            // clock across a process death and a derived one cannot.
            //
            // It also means a user who backed out of the gate and came back
            // is not told a lease expired, because none did. The gate must
            // not claim a history the user does not have.
            expired = leasesTakenThisCycle > 0,
        )
    }
}
