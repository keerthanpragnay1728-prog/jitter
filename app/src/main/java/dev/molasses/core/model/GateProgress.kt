package dev.molasses.core.model

/**
 * Live movement-gate feedback. The gate shows the failing [reason] rather than
 * a black box, because an unlock condition a user cannot see is
 * indistinguishable from a broken app.
 */
data class GateProgress(
    val fraction: Float = 0f,
    val reason: Reason = Reason.WAITING_TO_START,
    val path: Path = Path.NONE,
    /** Steps (path A) or detected peaks (path B) observed so far. */
    val events: Int = 0,
    val passed: Boolean = false,
) {
    enum class Path { NONE, STEP_DETECTOR, IMU_CADENCE, ALTERNATIVE_CHALLENGE }

    /** Which single check is currently blocking a pass. */
    enum class Reason {
        WAITING_TO_START,
        NEED_MORE_STEPS,
        CADENCE_TOO_SLOW,
        CADENCE_TOO_FAST,
        CADENCE_IRREGULAR,
        NOT_ENOUGH_MOTION,
        TOO_VIOLENT,
        MOTION_NOT_VERTICAL,
        PHONE_STATIONARY,
        SUSTAINING,
        PASSED,
        ;

        val isDisqualifying: Boolean
            get() = this == CADENCE_TOO_FAST || this == TOO_VIOLENT ||
                this == MOTION_NOT_VERTICAL || this == PHONE_STATIONARY
    }
}
