package dev.molasses.core.model

/**
 * Live movement-gate feedback, for display only. The gate shows the failing
 * [reason] rather than a black box, because an unlock condition a user cannot
 * see is indistinguishable from a broken app.
 *
 * There is deliberately no `passed` flag here. This type travels on a
 * conflating `StateFlow`, so anything terminal expressed through it can be
 * dropped before a collector runs, which is what made the first device build
 * unclearable. Passing is signalled by [GateOutcome] on its own channel, and
 * no collector should be able to infer a pass from progress.
 */
data class GateProgress(
    val fraction: Float = 0f,
    val reason: Reason = Reason.WAITING_TO_START,
    val path: Path = Path.NONE,
    /** Steps (path A) or detected peaks (path B) observed so far. */
    val events: Int = 0,
) {
    /**
     * Which pipeline produced this verdict. The two IMU paths are separate
     * calibration domains, not one path with two front ends, so they are
     * reported separately and stamped on every ledger row.
     */
    enum class Path {
        NONE,
        STEP_DETECTOR,

        /** `TYPE_LINEAR_ACCELERATION` + `TYPE_GRAVITY`, platform-fused. */
        IMU_FUSED,

        /** Raw `TYPE_ACCELEROMETER` through [dev.molasses.sensing.GravitySplitter]. */
        IMU_IIR,

        ALTERNATIVE_CHALLENGE,
        ;

        val isImu: Boolean get() = this == IMU_FUSED || this == IMU_IIR
    }

    /** Which single check is currently blocking a pass. */
    enum class Reason {
        WAITING_TO_START,
        NEED_MORE_STEPS,
        CADENCE_TOO_SLOW,
        CADENCE_TOO_FAST,
        CADENCE_IRREGULAR,

        /**
         * CV below the floor: more regular than human gait ever is. A
         * metronome, or a thumb tapping a phone that is lying still.
         */
        TOO_REGULAR,
        NOT_ENOUGH_MOTION,
        TOO_VIOLENT,
        MOTION_NOT_VERTICAL,
        PHONE_STATIONARY,
        SUSTAINING,
        PASSED,
        ;

        val isDisqualifying: Boolean
            get() = this == CADENCE_TOO_FAST || this == TOO_VIOLENT ||
                this == MOTION_NOT_VERTICAL || this == PHONE_STATIONARY ||
                this == TOO_REGULAR
    }
}
