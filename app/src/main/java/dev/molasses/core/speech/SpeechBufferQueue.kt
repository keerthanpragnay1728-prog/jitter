package dev.molasses.core.speech

/**
 * What Bit is allowed to say, and when.
 *
 * ## Why this is a rationing problem and not a queue
 * An unprompted companion that speaks whenever it has something to say reads
 * as nagging within a week, and a user who mutes it has lost the feature
 * permanently. So the budget is small and hard: three lines an hour, eight a
 * day, and nothing repeated inside a cycle. Those are not tuning knobs, they
 * are the reason the feature survives contact with a real week.
 *
 * ## Priority
 * [Priority.TELEPHONY] outranks [Priority.AMBIENT] and is exempt from the
 * rate caps. A missed call notice is information the user asked for by owning
 * a phone; an observation about scrolling is Bit's own idea. Rationing the
 * first to protect the user from the second would be the wrong trade, and a
 * user who misses a call because Bit had used up its hourly budget on
 * commentary would be right to uninstall.
 *
 * Telephony lines are still fully suppressed by [suppressed], because that
 * flag covers being in a call, being over a payment app, and having a gate
 * open, and none of those want any drawing at all.
 *
 * ## Counters are the caller's to persist
 * [spokenThisHour], [spokenToday] and [usedLineIds] are constructor inputs and
 * outputs so a process death cannot reset the budget. An in-memory counter
 * would make the cap trivially defeated by force-stopping the app, which is
 * exactly what an annoyed user does.
 *
 * Pure; no Android imports. Unit-tested in `SpeechBufferQueueTest`.
 */
class SpeechBufferQueue(
    private val maxPerHour: Int = MAX_PER_HOUR,
    private val maxPerDay: Int = MAX_PER_DAY,
) {

    enum class Priority { TELEPHONY, AMBIENT }

    /** One candidate line. [id] is what the no-repeat rule keys on. */
    data class Utterance(
        val id: String,
        val priority: Priority,
        val body: String,
    )

    /**
     * Everything the decision depends on, passed in rather than held, so the
     * whole class is a pure function and the caller owns persistence.
     *
     * @param nowMs monotonic. Only differences are used.
     * @param recentMs timestamps of lines already spoken, any age. Pruned here.
     * @param usedLineIds ids spoken in the current cycle, reset on rollover.
     * @param suppressed true during a call, over a financial app, or while a
     *   gate is open. Suppresses everything including telephony.
     */
    data class Context(
        val nowMs: Long,
        val recentMs: List<Long> = emptyList(),
        val usedLineIds: Set<String> = emptySet(),
        val suppressed: Boolean = false,
    )

    sealed interface Decision {
        /** Say it, and record [at] against the budget. */
        data class Speak(val utterance: Utterance, val at: Long) : Decision
        data class Drop(val reason: Reason) : Decision
    }

    enum class Reason { SUPPRESSED, HOURLY_CAP, DAILY_CAP, ALREADY_SAID, NOTHING_PENDING }

    /**
     * Pick at most one line from [candidates].
     *
     * Telephony first, then insertion order. Only the winner is charged
     * against the budget; the rest are simply not spoken, because a queue
     * that drains later would deliver stale observations about a scroll
     * session that ended twenty minutes ago.
     */
    fun next(candidates: List<Utterance>, context: Context): Decision {
        if (candidates.isEmpty()) return Decision.Drop(Reason.NOTHING_PENDING)
        if (context.suppressed) return Decision.Drop(Reason.SUPPRESSED)

        val ordered = candidates.sortedBy { if (it.priority == Priority.TELEPHONY) 0 else 1 }
        val fresh = ordered.filterNot { it.id in context.usedLineIds }
        if (fresh.isEmpty()) return Decision.Drop(Reason.ALREADY_SAID)

        val pick = fresh.first()

        // Telephony is exempt from the caps, but never from suppression or
        // from the no-repeat rule above.
        if (pick.priority == Priority.TELEPHONY) {
            return Decision.Speak(pick, context.nowMs)
        }

        val inLastHour = context.recentMs.count { context.nowMs - it in 0 until HOUR_MS }
        if (inLastHour >= maxPerHour) return Decision.Drop(Reason.HOURLY_CAP)

        val inLastDay = context.recentMs.count { context.nowMs - it in 0 until DAY_MS }
        if (inLastDay >= maxPerDay) return Decision.Drop(Reason.DAILY_CAP)

        return Decision.Speak(pick, context.nowMs)
    }

    /**
     * The timestamp list to persist after a [Decision.Speak].
     *
     * Entries older than a day are dropped, so the stored list stays at most
     * [maxPerDay] long and the caller never has to prune it separately.
     * Telephony lines are not recorded: they are exempt from the caps, and
     * recording them would let a busy afternoon of calls silence Bit's
     * ambient budget for the rest of the day.
     */
    fun record(decision: Decision.Speak, recentMs: List<Long>): List<Long> {
        if (decision.utterance.priority == Priority.TELEPHONY) {
            return prune(recentMs, decision.at)
        }
        return prune(recentMs + decision.at, decision.at)
    }

    private fun prune(list: List<Long>, nowMs: Long): List<Long> =
        list.filter { nowMs - it in 0 until DAY_MS }.sorted()

    companion object {
        const val MAX_PER_HOUR = 3
        const val MAX_PER_DAY = 8
        const val HOUR_MS = 60L * 60 * 1000
        const val DAY_MS = 24 * HOUR_MS
    }
}
