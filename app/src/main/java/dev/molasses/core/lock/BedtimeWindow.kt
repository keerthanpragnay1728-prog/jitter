package dev.molasses.core.lock

/**
 * How long `$ bedtime` locks for.
 *
 * ## Why a duration and not a deadline
 * `LockRegistry` stores a stamp and a duration, because that is the shape the
 * tamper clamp can defend. A wall-clock deadline of "06:00 tomorrow" is
 * defeated by setting the date forward, which needs no root and no tooling.
 * Converting to a duration at the moment the command is typed means bedtime
 * inherits the same RESTRICTION clamp as every other lock.
 *
 * ## The wake time is a constant, for now
 * There is no setting for it yet. Six is the hour that makes the command mean
 * what its name says on the widest range of schedules, and putting it here as
 * a named constant is the honest placeholder: when a setting arrives it
 * becomes the default rather than something to hunt for.
 *
 * Pure; no Android imports. Unit-tested in `BedtimeWindowTest`.
 */
object BedtimeWindow {

    const val MINUTES_PER_DAY = 24 * 60
    const val MS_PER_MINUTE = 60_000L

    /** 06:00 local. */
    const val WAKE_MINUTE_OF_DAY = 6 * 60

    /**
     * Milliseconds from [minuteOfDayNow] until the next [wakeMinuteOfDay].
     *
     * Always at least one minute. Typing `$ bedtime` at exactly 06:00 means a
     * full day, not a no-op: a command that silently did nothing at one minute
     * of the day would be the kind of edge the user only finds once, at the
     * moment they needed it.
     */
    fun durationMs(
        minuteOfDayNow: Int,
        wakeMinuteOfDay: Int = WAKE_MINUTE_OF_DAY,
    ): Long {
        val now = ((minuteOfDayNow % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY
        val wake = ((wakeMinuteOfDay % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY
        var minutes = wake - now
        if (minutes <= 0) minutes += MINUTES_PER_DAY
        return minutes * MS_PER_MINUTE
    }
}
