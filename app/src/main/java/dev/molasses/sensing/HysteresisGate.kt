package dev.molasses.sensing

/**
 * A Schmitt trigger on a lower bound. Pure.
 *
 * A single threshold on a noisy measurement chatters. Measured cadence over a
 * 3.5 s window is a 3 to 5 interval sample statistic, so a walker sitting near
 * the band edge crosses it repeatedly, and every crossing used to zero the
 * sustain streak. Separating the entry and exit thresholds means a measurement
 * has to genuinely leave the band before it counts as leaving, not merely
 * wobble across one line.
 *
 * @param enterAt value at or above which an outside gate moves inside.
 * @param exitAt value below which an inside gate moves outside. Must not
 *   exceed [enterAt].
 */
class HysteresisGate(
    val enterAt: Double,
    val exitAt: Double,
) {
    init {
        require(exitAt <= enterAt) {
            "exitAt ($exitAt) must not exceed enterAt ($enterAt); that inverts the hysteresis"
        }
    }

    var isInside: Boolean = false
        private set

    /** True when the value has ever entered and has not yet left. */
    fun update(value: Double): Boolean {
        isInside = if (isInside) value >= exitAt else value >= enterAt
        return isInside
    }

    fun reset() {
        isInside = false
    }
}
