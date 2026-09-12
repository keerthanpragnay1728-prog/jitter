package dev.molasses.core.time

/**
 * Two clocks, deliberately separate.
 *
 * [MonotonicClock] is `SystemClock.elapsedRealtime()`: it never runs backwards
 * while the device is up, but it resets to 0 on boot. All *duration* accounting
 * uses it.
 *
 * [WallClock] is `System.currentTimeMillis()`: it survives reboot but the user
 * can set it to anything. Only cycle anchoring and abstinence windows use it,
 * and only through [ClockTamperClamp].
 */
fun interface MonotonicClock {
    fun elapsedMs(): Long
}

fun interface WallClock {
    fun wallMs(): Long
}
