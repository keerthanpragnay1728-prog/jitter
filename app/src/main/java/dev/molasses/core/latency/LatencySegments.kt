package dev.molasses.core.latency

/**
 * The four segments of stall latency (SS6).
 *
 * Decomposed rather than measured end-to-end because a single number cannot
 * distinguish "our code is slow" from "the platform is slow", and only one of
 * those is fixable.
 */
enum class Segment(val label: String, val meaning: String) {
    /**
     * `AccessibilityEvent.getEventTime()` -> entry to `onAccessibilityEvent`.
     * Time burned before any of our code runs. **Not optimisable.** If A alone
     * exceeds ~80 ms the concept is dead regardless of implementation.
     */
    A("A", "pipeline: event time -> our callback"),

    /** Callback entry -> `updateViewLayout` returns. The only segment we control. */
    B("B", "our code: callback -> updateViewLayout returns"),

    /**
     * `updateViewLayout` returns -> next frame callback. WindowManagerService
     * relayout and InputDispatcher window-handle refresh.
     *
     * This segment exists because `updateViewLayout` returning means only that
     * the change is *queued* to WMS. The flag is not live in the input
     * dispatcher until WMS relayouts and InputDispatcher refreshes its window
     * handles, one or more frames later. A probe that stops at
     * `updateViewLayout` under-reports true absorption latency by 16-33 ms or
     * more.
     */
    C("C", "relayout: updateViewLayout -> next frame"),

    /**
     * Scroll `eventTime` -> `eventTime` of the first MotionEvent the sink
     * actually consumes. **The real answer.** Everything else is diagnostic.
     */
    D("D", "ground truth: scroll -> first absorbed touch"),
}

/**
 * Fixed-size ring of samples with percentile readout.
 *
 * Percentiles, not a moving average: latency distributions are right-skewed,
 * and a mean hides exactly the tail that breaks the illusion. A p50 of 60 ms
 * with a p95 of 250 ms is a product that feels broken one flick in twenty,
 * and a mean would report 70 ms and call it fine.
 *
 * Pure: no Android imports, so the percentile arithmetic is unit-tested.
 * Not thread-safe; callers confine it to the main thread.
 */
class LatencyRing(val capacity: Int = DEFAULT_CAPACITY) {

    private val values = LongArray(capacity)
    private var writeIndex = 0
    private var filled = 0

    /** Samples discarded as negative. See [add]. */
    var discarded = 0
        private set

    val size: Int get() = filled

    /**
     * @return true if recorded, false if discarded.
     *
     * Negative samples are discarded rather than recorded. On segment D the
     * sink can consume a touch that was already in flight when the stall
     * armed, whose `eventTime` precedes the scroll -- recording it would
     * report an absurdly low or negative latency and flatter the result. The
     * discard count is reported alongside so the filtering is visible.
     */
    fun add(valueMs: Long): Boolean {
        if (valueMs < 0) {
            discarded++
            return false
        }
        values[writeIndex] = valueMs
        writeIndex = (writeIndex + 1) % capacity
        if (filled < capacity) filled++
        return true
    }

    fun clear() {
        writeIndex = 0
        filled = 0
        discarded = 0
    }

    fun snapshot(): LongArray = LongArray(filled) { values[it] }.also { it.sort() }

    /** Nearest-rank percentile. [p] in 0.0..1.0. */
    fun percentile(p: Double): Long {
        if (filled == 0) return -1
        val sorted = snapshot()
        val rank = Math.ceil(p.coerceIn(0.0, 1.0) * sorted.size).toInt().coerceAtLeast(1)
        return sorted[(rank - 1).coerceIn(0, sorted.size - 1)]
    }

    /**
     * Most recently added sample, or -1. Note this is *not* `snapshot().last()`
     * -- that returns the largest value, since the snapshot is sorted.
     */
    val last: Long
        get() = if (filled == 0) -1 else values[(writeIndex - 1 + capacity) % capacity]

    val p50: Long get() = percentile(0.50)
    val p95: Long get() = percentile(0.95)
    val min: Long get() = if (filled == 0) -1 else snapshot().first()
    val max: Long get() = if (filled == 0) -1 else snapshot().last()

    companion object {
        const val DEFAULT_CAPACITY = 100
    }
}

/** One package's four rings. */
class PackageLatency(val pkg: String, capacity: Int = LatencyRing.DEFAULT_CAPACITY) {
    private val rings = Segment.entries.associateWith { LatencyRing(capacity) }

    operator fun get(segment: Segment): LatencyRing = rings.getValue(segment)

    fun record(segment: Segment, valueMs: Long): Boolean = rings.getValue(segment).add(valueMs)

    fun clear() = rings.values.forEach { it.clear() }

    /**
     * The per-event logcat line, e.g.
     * `[TARGET: com.instagram.android] A=34 B=2 C=18 D=71 | p50(D)=68 p95(D)=112 n=50`
     */
    fun formatLine(a: Long, b: Long, c: Long, d: Long): String {
        val dRing = rings.getValue(Segment.D)
        return buildString {
            append("[TARGET: ").append(pkg).append("] ")
            append("A=").append(a)
            append(" B=").append(b)
            append(" C=").append(c)
            append(" D=").append(d)
            append(" | p50(D)=").append(dRing.p50)
            append(" p95(D)=").append(dRing.p95)
            append(" n=").append(dRing.size)
            if (dRing.discarded > 0) append(" discarded=").append(dRing.discarded)
        }
    }

    /** Per-segment percentile table, for `dumpsys` and the debug screen. */
    fun formatTable(): String = buildString {
        append("[TARGET: ").append(pkg).append("]\n")
        append("  seg  n   min   p50   p95   max  discarded  meaning\n")
        for (seg in Segment.entries) {
            val r = rings.getValue(seg)
            append(
                "  %-3s %3d %5d %5d %5d %5d %10d  %s\n".format(
                    seg.label, r.size, r.min, r.p50, r.p95, r.max, r.discarded, seg.meaning,
                ),
            )
        }
    }
}

/** All packages. */
class LatencyRegistry(private val capacity: Int = LatencyRing.DEFAULT_CAPACITY) {
    private val byPackage = LinkedHashMap<String, PackageLatency>()

    fun forPackage(pkg: String): PackageLatency =
        byPackage.getOrPut(pkg) { PackageLatency(pkg, capacity) }

    fun packages(): List<String> = byPackage.keys.toList()

    fun clear() = byPackage.values.forEach { it.clear() }

    fun formatAll(): String =
        if (byPackage.isEmpty()) "no stall latency recorded yet\n"
        else byPackage.values.joinToString("\n") { it.formatTable() }
}
