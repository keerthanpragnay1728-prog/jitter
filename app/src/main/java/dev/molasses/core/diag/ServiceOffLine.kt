package dev.molasses.core.diag

/**
 * When the console says the service is off. Pure: the caller passes the
 * service's state, whether the console is visible, and `elapsedRealtime`.
 *
 * ## Why a grace
 * A boot starts the launcher before the accessibility service connects, so
 * for the first few seconds of every boot the service reads as not working
 * through no fault of anyone's. A line shown then would say "off" about a
 * service that is starting, on the one screen the user sees first. So the
 * line waits until the service has been not working for [GRACE_MS]
 * continuously, measured from when the console became visible, and goes the
 * moment the service works.
 *
 * Measured while the console is visible because that is when it is read.
 * Leaving the console resets the clock, so a return starts the grace again
 * rather than inheriting a count that ran while nobody could see the line.
 */
object ServiceOffLine {

    const val GRACE_MS: Long = 10_000L

    /**
     * When the service was first read as not working, continuously, while
     * the console was visible. Null when it works or the console is not
     * visible.
     */
    fun since(previous: Long?, working: Boolean, visible: Boolean, nowMs: Long): Long? = when {
        working || !visible -> null
        else -> previous ?: nowMs
    }

    /** Shown after the grace, and never while the service works. */
    fun shown(since: Long?, working: Boolean, nowMs: Long): Boolean =
        !working && since != null && nowMs - since >= GRACE_MS
}
