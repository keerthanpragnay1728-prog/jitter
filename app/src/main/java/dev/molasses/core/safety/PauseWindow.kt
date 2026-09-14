package dev.molasses.core.safety

import dev.molasses.core.time.StampedInstant

/**
 * "Pause Jitter for 15 minutes": the escape hatch for the case where a bank
 * warns anyway and the user needs the service quiet right now, at a till,
 * without navigating an accessibility settings page.
 *
 * ## This is not a friction holiday
 * A pause suppresses **overlays only**. Foreground time keeps accumulating,
 * `tierIndex` keeps climbing, and the ledger keeps recording, so coming back
 * after a pause lands the user exactly where they would have been. That
 * distinction is the whole reason a pause is safe to offer: if it rewound
 * anything, it would be the bypass every other defence in this app exists to
 * prevent, reachable from a button in settings.
 *
 * ## Why this does not reuse the cycle's clamp
 * `ClockTamperClamp` credits `min(wall delta, elapsed delta)` when the two
 * clocks disagree, because for the cycle window under-crediting is the safe
 * direction: it means the cycle takes longer to reset, so the user gets more
 * friction, not less.
 *
 * For a pause that is exactly backwards. Under-crediting keeps the pause open
 * longer, which means less friction, so winding the system clock back an hour
 * would hold a fifteen minute pause open indefinitely. That is a friction
 * holiday reachable from a button in settings, which is the thing every other
 * defence in this app exists to prevent.
 *
 * So a pause is measured on `elapsedRealtime` alone. It is monotonic within a
 * boot, it counts sleep, and it cannot be set. A reboot ends the pause
 * outright: `elapsedRealtime` restarts at zero and there is no reading that
 * spans the boot, and expiring is the safe direction here in a way it is not
 * for the cycle anchor.
 *
 * The rule is worth stating once, because it decides every future deadline in
 * this app: measure with the clock whose failure mode costs the user friction,
 * never the one whose failure mode grants it.
 *
 * Pure; no Android imports. Unit-tested in `PauseWindowTest`.
 */
object PauseWindow {

    /** The only offered duration. One button, no picker, no decision to make. */
    const val DURATION_MS = 15L * 60 * 1000

    /**
     * Milliseconds left in the pause, or 0 when it is not active.
     *
     * [startedAt] is [StampedInstant.UNSET] when no pause has ever been
     * armed, which reports 0 rather than a full window. That is the opposite
     * of `CycleWindow.remainingMs`, and deliberately so: an unset cycle
     * anchor means "a full window is about to start", while an unset pause
     * means "not paused".
     */
    fun remainingMs(
        startedAt: StampedInstant,
        now: StampedInstant,
        durationMs: Long = DURATION_MS,
    ): Long {
        if (!startedAt.isSet) return 0L
        // A reboot ends it. elapsedRealtime restarted, so the stored reading
        // is not comparable and the only safe answer is "expired".
        if (startedAt.bootId != now.bootId) return 0L
        val age = now.elapsedMs - startedAt.elapsedMs
        // A negative age means the stored stamp is from the future, which
        // within one boot can only be a corrupt read. Treat it as expired
        // rather than as a very long pause.
        if (age < 0L) return 0L
        return (durationMs - age).coerceIn(0L, durationMs)
    }

    /** True while overlays must stay suppressed. */
    fun isActive(
        startedAt: StampedInstant,
        now: StampedInstant,
        durationMs: Long = DURATION_MS,
    ): Boolean = remainingMs(startedAt, now, durationMs) > 0L
}
