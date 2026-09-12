package dev.molasses.sensing

/**
 * Separates gravity from linear acceleration with a first-order IIR low-pass,
 * for the fallback path where no fused `TYPE_LINEAR_ACCELERATION` exists.
 *
 * ## Why alpha is computed per sample and not hardcoded
 * `alpha` is only meaningful together with a sample interval:
 * `tau = alpha * dt / (1 - alpha)`. `SENSOR_DELAY_GAME` is a *hint*, not a
 * contract -- real devices deliver anywhere from ~16 ms to ~25 ms, some OEMs
 * ignore it under battery saver, and a few deliver 200 Hz. A hardcoded alpha
 * therefore means a time constant that drifts with the device:
 *
 * | rate   | tau with alpha = 0.97 | corner  |
 * |--------|-----------------------|---------|
 * | 25 Hz  | 1.29 s                | 0.12 Hz |
 * | 50 Hz  | 0.65 s                | 0.25 Hz |
 * | 100 Hz | 0.32 s                | 0.49 Hz |
 * | 200 Hz | 0.16 s                | 0.98 Hz |
 *
 * At 200 Hz the corner has climbed back into the gait band, which is the exact
 * failure this filter exists to avoid. So the time constant is the fixed
 * quantity and alpha is derived from the sensor's own clock:
 *
 * ```
 * alpha = tau / (tau + dt)
 * ```
 *
 * Pure: arithmetic on a Long and three Doubles, no Android imports.
 */
class GravitySplitter(
    /**
     * Gravity time constant. 0.65 s puts the corner at 1/(2*pi*tau) = 0.245 Hz,
     * roughly a decade below the 1.2-2.6 Hz gait band, so the band passes into
     * linear acceleration essentially unattenuated (~99%).
     *
     * The previous value was 80 ms (corner 1.99 Hz) -- mid-band, removing about
     * 35% of the signal the analyser was trying to measure.
     */
    val tauSeconds: Double = DEFAULT_TAU_SECONDS,
) {
    var gravityX = 0.0
        private set
    var gravityY = 0.0
        private set
    var gravityZ = 0.0
        private set

    /** Linear acceleration from the most recent [update]. */
    var linearX = 0.0
        private set
    var linearY = 0.0
        private set
    var linearZ = 0.0
        private set

    var primed = false
        private set

    private var lastTimestampNs = 0L

    /** Alpha realised on the most recent sample. Diagnostic. */
    var lastAlpha = 0.0
        private set

    /** dt realised on the most recent sample, seconds. Diagnostic. */
    var lastDtSeconds = 0.0
        private set

    fun reset() {
        primed = false
        lastTimestampNs = 0
        gravityX = 0.0; gravityY = 0.0; gravityZ = 0.0
        linearX = 0.0; linearY = 0.0; linearZ = 0.0
        lastAlpha = 0.0; lastDtSeconds = 0.0
    }

    /**
     * @param timestampNs `SensorEvent.timestamp`: nanoseconds on the monotonic
     *   device-uptime base.
     * @param x/y/z raw acceleration including gravity, m/s^2.
     */
    fun update(timestampNs: Long, x: Double, y: Double, z: Double) {
        if (!primed) {
            // Seed gravity from the first sample rather than from zero.
            // Starting at zero makes the filter spend ~3*tau (about 2 s here)
            // converging, and the gate's first two seconds would be measured
            // against a gravity vector that is mostly wrong -- which shows up
            // as a spurious tilt reading and inflated linear acceleration.
            gravityX = x; gravityY = y; gravityZ = z
            linearX = 0.0; linearY = 0.0; linearZ = 0.0
            lastTimestampNs = timestampNs
            lastAlpha = 0.0
            lastDtSeconds = 0.0
            primed = true
            return
        }

        val dt = ((timestampNs - lastTimestampNs) / 1_000_000_000.0)
            // Reject bursts (batched delivery replaying with near-zero gaps)
            // and gaps (a doze wake-up). Either would otherwise swing alpha to
            // an extreme for one sample.
            .coerceIn(MIN_DT_SECONDS, MAX_DT_SECONDS)
        lastTimestampNs = timestampNs
        lastDtSeconds = dt

        val alpha = tauSeconds / (tauSeconds + dt)
        lastAlpha = alpha

        gravityX = alpha * gravityX + (1 - alpha) * x
        gravityY = alpha * gravityY + (1 - alpha) * y
        gravityZ = alpha * gravityZ + (1 - alpha) * z

        linearX = x - gravityX
        linearY = y - gravityY
        linearZ = z - gravityZ
    }

    companion object {
        const val DEFAULT_TAU_SECONDS = 0.65

        /** 500 Hz ceiling. */
        const val MIN_DT_SECONDS = 0.002

        /** 10 Hz floor. */
        const val MAX_DT_SECONDS = 0.100

        /** Corner frequency implied by a time constant, Hz. */
        fun cornerHz(tauSeconds: Double): Double = 1.0 / (2 * Math.PI * tauSeconds)

        /** Time constant implied by a fixed alpha at a given rate. */
        fun tauFor(alpha: Double, dtSeconds: Double): Double =
            alpha * dtSeconds / (1 - alpha)
    }
}
