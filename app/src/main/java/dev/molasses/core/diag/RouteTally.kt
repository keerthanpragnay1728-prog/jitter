package dev.molasses.core.diag

import dev.molasses.core.session.EventRoute
import dev.molasses.core.session.WindowEvent

/**
 * Per-package counts of accessibility events, split by what the router did
 * with them.
 *
 * ## Why the split is the whole point
 * A raw event count cannot tell the two interesting failures apart:
 *
 *  * **No events at all.** The service is not bound, or `packageNames` does
 *    not include the app.
 *  * **Events arriving and being dropped.** They reach `onAccessibilityEvent`
 *    and the router returns `Ignore` for every one, which is what happens
 *    when the stored target list is empty: `packageNames` widens to every app
 *    on the device while `targets` stays empty, so the service sees maximum
 *    traffic and routes none of it.
 *
 * Both look identical from the outside, and the second produces the worst
 * possible combination of battery cost and zero behaviour. Counting routed
 * and ignored separately, per package, distinguishes them at a glance.
 *
 * Not thread safe by itself. The caller keeps it on the accessibility
 * callback thread, which the platform serialises, and copies out through
 * [snapshot] for display.
 *
 * Pure; no Android imports. Unit-tested in `RouteTallyTest`.
 */
class RouteTally(
    /**
     * Cap on distinct packages held. With an empty target list every app on
     * the device arrives here, and an unbounded map in a service that runs for
     * weeks is a leak. Overflow is counted rather than silently dropped,
     * because "more packages than expected" is itself the diagnosis.
     */
    private val maxPackages: Int = DEFAULT_MAX_PACKAGES,
) {

    data class PackageTally(
        val scrolled: Long = 0,
        val windowState: Long = 0,
        val windowsChanged: Long = 0,
        val routed: Long = 0,
        val ignored: Long = 0,
    ) {
        val total: Long get() = scrolled + windowState + windowsChanged
    }

    private val counts = LinkedHashMap<String, PackageTally>()
    private var overflowed = 0L

    /** Packages seen beyond [maxPackages]. Non-zero is itself a finding. */
    val overflowedPackages: Long get() = overflowed

    fun record(event: WindowEvent, route: EventRoute) {
        val key = event.packageName.ifEmpty { UNKNOWN }
        val existing = counts[key]
        if (existing == null && counts.size >= maxPackages) {
            overflowed += 1
            return
        }
        val t = existing ?: PackageTally()
        val ignored = route == EventRoute.Ignore
        counts[key] = t.copy(
            scrolled = t.scrolled + if (event.kind == WindowEvent.Kind.VIEW_SCROLLED) 1 else 0,
            windowState = t.windowState +
                if (event.kind == WindowEvent.Kind.WINDOW_STATE_CHANGED) 1 else 0,
            windowsChanged = t.windowsChanged +
                if (event.kind == WindowEvent.Kind.WINDOWS_CHANGED) 1 else 0,
            routed = t.routed + if (ignored) 0 else 1,
            ignored = t.ignored + if (ignored) 1 else 0,
        )
    }

    /** Busiest first, so the interesting row is at the top of the screen. */
    fun snapshot(): List<Pair<String, PackageTally>> =
        counts.entries
            .map { it.key to it.value }
            .sortedByDescending { it.second.total }

    fun reset() {
        counts.clear()
        overflowed = 0L
    }

    companion object {
        const val DEFAULT_MAX_PACKAGES = 32
        const val UNKNOWN = "(none)"
    }
}
