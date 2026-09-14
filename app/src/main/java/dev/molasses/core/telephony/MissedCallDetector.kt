package dev.molasses.core.telephony

/**
 * Was that a missed call?
 *
 * ## The rule
 * A call is missed when the phone goes `RINGING` and returns to `IDLE`
 * **without passing through `OFFHOOK`**. `OFFHOOK` means the call was
 * connected, whether the user answered it or placed it, so any ring that
 * reaches it was not missed.
 *
 * That single rule also covers rejection, which is the case worth being
 * explicit about: rejecting a call produces exactly the same `RINGING` to
 * `IDLE` transition as letting it ring out. The two are indistinguishable from
 * call state alone, and this detector reports both as missed. That is the
 * right answer for a launcher notice ("you missed a call from X") and would be
 * the wrong answer for a call log, which is not what this feeds.
 *
 * ## Deliberately source agnostic
 * This takes [State] values and nothing else. It does not know whether they
 * came from `TelephonyManager` (which needs `READ_PHONE_STATE` and
 * `READ_CALL_LOG`) or were derived from a dialer notification (which needs
 * neither).
 *
 * The chosen source is the notification listener, because the two telephony
 * permissions are exactly what the banking-app work in `SensitivePackages`
 * removed, and the listener is being built anyway. A dialer notification is
 * a single event rather than a stream, so the adapter synthesises
 * `RINGING` then `IDLE` and the intermediate states never arrive.
 *
 * **The `OFFHOOK` branches are therefore unreachable under the current
 * source.** They are kept deliberately, not left by accident: if an OEM
 * dialer posts a notification this app cannot read, the telephony source has
 * to be swappable without touching or re-testing this logic. Each is marked
 * below. Do not delete them as dead code.
 *
 * ## Starting mid-call
 * The first state ever seen is treated as a baseline, not as a transition. A
 * process that starts while a call is already connected sees `OFFHOOK` first
 * and must not read the following `IDLE` as a missed call. [hasBaseline]
 * exposes this so a caller can tell "no calls yet" from "not listening yet".
 *
 * Pure; no Android imports. Unit-tested in `MissedCallDetectorTest`.
 */
class MissedCallDetector {

    enum class State { IDLE, RINGING, OFFHOOK }

    /** What one state transition meant. */
    sealed interface Event {
        /** Nothing worth reporting. */
        data object None : Event

        /** A ring ended without ever connecting. */
        data class Missed(val callerId: String?) : Event

        /** A ring connected, or a call was placed. */
        data object Connected : Event

        /** A connected call ended. */
        data object Ended : Event
    }

    private var current: State? = null
    private var sawOffHookThisCall = false
    private var ringingCallerId: String? = null

    /** False until the first state has been observed. */
    val hasBaseline: Boolean get() = current != null

    /** The state as last seen, or null before the first observation. */
    val state: State? get() = current

    /**
     * Feed one state.
     *
     * @param callerId identity if the source has it. Null is normal and must
     *   degrade to a nameless notice rather than suppressing it: a missed call
     *   the user is not told about is worse than one attributed to "unknown".
     */
    fun onState(next: State, callerId: String? = null): Event {
        val previous = current
        current = next

        // Hold the identity seen while ringing. By the time the state returns
        // to IDLE the source has usually stopped reporting a number, so the
        // one captured at RINGING is the only one available.
        if (next == State.RINGING && callerId != null) ringingCallerId = callerId

        if (previous == null) {
            // Baseline. A process that starts mid-call sees OFFHOOK here and
            // must remember that, or the IDLE that follows reads as a miss.
            // Also unreachable under the notification source, and also kept.
            sawOffHookThisCall = next == State.OFFHOOK
            return Event.None
        }

        if (next == previous) return Event.None

        return when (next) {
            State.RINGING -> {
                // A new ring. Reset the connected flag, but only when arriving
                // from IDLE: RINGING during an OFFHOOK call is call waiting,
                // and the original call is still connected.
                // The RINGING-during-OFFHOOK case below is call waiting, and
                // is likewise unreachable under the notification source.
                if (previous == State.IDLE) {
                    sawOffHookThisCall = false
                    if (callerId == null) ringingCallerId = null
                }
                Event.None
            }

            // Unreachable under the notification source, which never
            // reports a connected call. Kept for the telephony source.
            State.OFFHOOK -> {
                sawOffHookThisCall = true
                Event.Connected
            }

            State.IDLE -> {
                val missed = previous == State.RINGING && !sawOffHookThisCall
                val id = ringingCallerId
                sawOffHookThisCall = false
                ringingCallerId = null
                if (missed) Event.Missed(id) else Event.Ended
            }
        }
    }

    /** Drop all state, as on losing the source. */
    fun reset() {
        current = null
        sawOffHookThisCall = false
        ringingCallerId = null
    }
}
