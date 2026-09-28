package dev.molasses.core.remind

import dev.molasses.core.time.StampedInstant
import dev.molasses.core.time.TimeParser
import dev.molasses.core.util.DateMath

/**
 * One reminder. [due] is the instant it should fire, stamped on all three
 * clocks at the moment it was set. [fired] is set once it has gone off, and
 * from then until the user dismisses it on the console it is the console's
 * to show. [late] records that it fired after its time because it was found
 * already due when rescheduled.
 */
data class Reminder(
    val id: Long,
    val text: String,
    val due: StampedInstant,
    val fired: Boolean = false,
    val late: Boolean = false,
)

/**
 * `$ rem`, as pure functions over the stored list.
 *
 * ## The list is the queue
 * A fired reminder that has not been dismissed is the console's to show, and
 * it stays in this list until dismissed. That is deliberately not
 * `ConsoleSpeech`: a reminder is something the user asked for, not something
 * Bit volunteered, so it does not count against the three an hour and eight
 * a day, it cannot be dropped by the depth-one unprompted queue, and because
 * this list is in the DataStore it survives a process death between firing
 * and the next visit to the launcher.
 *
 * ## Why a cap
 * Twenty. A reminder is state the user will come back to read, which is the
 * thing the console otherwise refuses to hold (see CLAUDE.md, the boundary
 * section, for why this command is the exception). A cap keeps the exception
 * the size of a note rather than a task manager, and the twenty first is
 * refused plainly rather than dropping the oldest, which would lose something
 * the user asked to be told.
 *
 * Pure; no Android imports. Unit-tested in `ReminderBookTest`.
 */
object ReminderBook {

    const val MAX = 20

    sealed interface When {
        /** A time of day, the next time it comes round. */
        data class At(val minuteOfDay: Int) : When

        /** A duration from now. */
        data class In(val durationMs: Long) : When

        /**
         * A date and a time of day, as in `rem 3 oct 9am`. The date is read
         * by [DateMath.spec], the same rules `$ days` uses, and resolved
         * forward from today only when the reminder is set.
         */
        data class On(val date: DateMath.DateSpec, val minuteOfDay: Int) : When
    }

    sealed interface Added {
        data class Ok(val list: List<Reminder>, val reminder: Reminder) : Added

        /** [MAX] are already pending. Nothing was added. */
        data object Full : Added
    }

    /**
     * The instant [whenSpec] names, from [now], or null when it is not in the
     * future.
     *
     * A time of day equal to the current minute means tomorrow, never zero,
     * the same rule `$ alarm` follows through [TimeParser.msUntil], so [When.At]
     * and [When.In] are never null. [When.On] names one instant and does not
     * roll forward: a date and time not after [now] is null, and the caller
     * refuses it.
     *
     * @param wallOn the wall-clock epoch ms of a date and a minute of the day,
     *   or null when the date cannot be resolved. Supplied by the host, because
     *   it needs today's date and the time zone, and neither belongs in the
     *   pure set (see PurityTest's allowance). Only [When.On] calls it.
     */
    fun dueAt(
        whenSpec: When,
        now: StampedInstant,
        nowMinuteOfDay: Int,
        wallOn: (DateMath.DateSpec, Int) -> Long?,
    ): StampedInstant? {
        val deltaMs = when (whenSpec) {
            is When.At -> TimeParser.msUntil(nowMinuteOfDay, whenSpec.minuteOfDay)
            is When.In -> whenSpec.durationMs
            is When.On -> {
                val dueWall = wallOn(whenSpec.date, whenSpec.minuteOfDay) ?: return null
                val delta = dueWall - now.wallMs
                if (delta <= 0L) return null
                delta
            }
        }
        return StampedInstant(now.wallMs + deltaMs, now.elapsedMs + deltaMs, now.bootId)
    }

    /** Pending means not yet dismissed, fired or not. The cap counts these. */
    fun add(list: List<Reminder>, id: Long, text: String, due: StampedInstant): Added {
        if (list.size >= MAX) return Added.Full
        val reminder = Reminder(id = id, text = text.trim(), due = due)
        return Added.Ok(list + reminder, reminder)
    }

    /**
     * Whether [reminder] is due at [now]. On the same boot the monotonic
     * clock decides, so moving the wall clock cannot fire one early or hold
     * one back. Across a reboot only the wall clock is left.
     */
    fun isDue(reminder: Reminder, now: StampedInstant): Boolean =
        if (reminder.due.bootId == now.bootId) now.elapsedMs >= reminder.due.elapsedMs
        else now.wallMs >= reminder.due.wallMs

    sealed interface Step {
        /** Set an alarm for [atWallMs]. */
        data class Schedule(val reminder: Reminder, val atWallMs: Long) : Step

        /** Already past due: fire now, marked late. */
        data class FireLate(val reminder: Reminder) : Step
    }

    /**
     * What to do with every unfired reminder after a boot or a service
     * connect: schedule the ones still ahead, fire the ones already due.
     *
     * The alarm is set on the wall clock, because an AlarmManager elapsed
     * alarm does not survive a reboot and this is the path that re-arms after
     * one. On the same boot the wall time is derived from the remaining
     * monotonic time, so a wall clock moved since the reminder was set does
     * not move when it fires.
     */
    fun reschedule(list: List<Reminder>, now: StampedInstant): List<Step> =
        list.filterNot { it.fired }.map { r ->
            if (isDue(r, now)) {
                Step.FireLate(r)
            } else {
                val atWall = if (r.due.bootId == now.bootId) now.wallMs + (r.due.elapsedMs - now.elapsedMs)
                else r.due.wallMs
                Step.Schedule(r, atWall)
            }
        }

    /** [id] has gone off. No-op for an unknown or already fired id. */
    fun fired(list: List<Reminder>, id: Long, late: Boolean): List<Reminder> =
        list.map { if (it.id == id && !it.fired) it.copy(fired = true, late = late) else it }

    /** Removed when the user dismisses it, never when it fires. */
    fun dismissed(list: List<Reminder>, id: Long): List<Reminder> = list.filterNot { it.id == id }

    /** How many a bare `rem` lists at most. */
    const val LIST_MAX = 5

    /**
     * What a bare `rem` lists: unfired reminders, soonest first, ties by id,
     * at most [LIST_MAX]. Never a fired one; see `Command.RemList`.
     */
    fun pending(list: List<Reminder>): List<Reminder> =
        list.filter { !it.fired }.sortedWith(compareBy({ it.due.wallMs }, { it.id })).take(LIST_MAX)

    /** What the console shows, oldest due first. */
    fun toShow(list: List<Reminder>): List<Reminder> =
        list.filter { it.fired }.sortedWith(compareBy({ it.due.wallMs }, { it.id }))

    /** An id not in [list]. Monotonic, so a dismissed id is never reused while any alarm could still name it. */
    fun nextId(list: List<Reminder>, lastIssued: Long): Long =
        maxOf(lastIssued, list.maxOfOrNull { it.id } ?: 0L) + 1
}
