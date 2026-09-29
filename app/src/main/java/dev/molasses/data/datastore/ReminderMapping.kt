package dev.molasses.data.datastore

import dev.molasses.ReminderEntry
import dev.molasses.core.remind.Reminder
import dev.molasses.core.time.StampedInstant

/**
 * [Reminder] to and from the proto. Lossless both ways, like `LockMapping`
 * and `LeaseMapping` beside it.
 */

fun Reminder.toProto(): ReminderEntry = ReminderEntry.newBuilder()
    .setId(id)
    .setText(text)
    .setDueWallMs(due.wallMs)
    .setDueElapsedMs(due.elapsedMs)
    .setDueBootId(due.bootId)
    .setFired(fired)
    .setLate(late)
    .build()

fun ReminderEntry.toReminder(): Reminder = Reminder(
    id = id,
    text = text,
    due = StampedInstant(wallMs = dueWallMs, elapsedMs = dueElapsedMs, bootId = dueBootId),
    fired = fired,
    late = late,
)
