package dev.molasses.overlay

import android.content.Context
import android.media.AudioManager
import android.util.Log

/**
 * "Is the user on a call right now."
 *
 * ## Why `AudioManager.getMode()` and not telephony
 * It needs no grant, and it catches VoIP, which
 * `TelephonyCallback.CallStateListener` misses entirely. `READ_PHONE_STATE` is
 * deliberately not requested by this app and `AccessibilityConfigTest` asserts
 * it stays out, so a telephony listener cannot register on any build that
 * ships.
 *
 * There was one anyway, registered beside this and described as a secondary.
 * It is deleted. A path that needs a grant the manifest forbids, and that
 * would have seen a strict subset of what this sees even with the grant, is
 * not a fallback; `MODE_IN_CALL` covers the cellular call telephony would have
 * reported and `MODE_IN_COMMUNICATION` covers the VoIP call it would not.
 *
 * So this is not the primary among two. It is the check.
 *
 * `MODE_IN_COMMUNICATION` is also set by some assistant and voice recording
 * flows, so this occasionally reports a call with none in progress. That is a
 * false positive, and every caller uses this to decide whether to *stop* doing
 * something, so it costs a stall or a gate rather than a phone call.
 *
 * ## Why "cannot tell" is a third answer
 * [inProgressOrNull] returns null when there is no `AudioManager` to ask or
 * the read throws. It used to return false there, which is the one answer that
 * is never safe: "no call in progress" is what unblocks an armed touch sink
 * over whatever is on screen, and a panic path that fails open is worse than
 * no panic path.
 *
 * It does not simply return true instead, because the two callers want
 * opposite things from an unanswerable question and only they can say which.
 * So [inProgress] takes the direction as an argument with no default, the same
 * way `ClockTamperClamp` makes every deadline name its own. See the two call
 * sites: the shutter passes true, the launch gate passes false, and both say
 * why.
 *
 * ## Why it is a class and not two lines at each call site
 * It was two lines inside the shutter, and the launch gate needs the same
 * answer for the same reason. A safety check with two copies is a safety check
 * with two behaviours as soon as one of them is edited.
 */
class CallDetector(context: Context) {

    private val audio: AudioManager? =
        context.applicationContext.getSystemService(AudioManager::class.java)

    /**
     * Non-null when this detector cannot answer, with the reason.
     *
     * Set at construction when the system service is missing and on the first
     * read that throws. Exposed rather than swallowed so the failure is
     * findable: this is the primary call check, and it going quiet is
     * invisible from the outside because the answer it gives is still a
     * boolean.
     */
    @Volatile
    var unavailable: String? = null
        private set

    init {
        if (audio == null) {
            unavailable = "no AudioManager"
            Log.w(TAG, "no AudioManager: the call check cannot answer")
        }
    }

    /**
     * True on a call, false when definitely not, **null when it cannot tell**.
     */
    fun inProgressOrNull(): Boolean? {
        val am = audio ?: return null
        val mode = try {
            am.mode
        } catch (e: Exception) {
            if (unavailable == null) {
                unavailable = e::class.java.simpleName + ": " + e.message
                Log.w(TAG, "audio mode read failed: the call check cannot answer", e)
            }
            return null
        }
        return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
    }

    /**
     * @param whenUnknown the answer when this detector cannot tell. No
     *   default: an unanswerable call check is exactly the case where an
     *   inherited default would be wrong somewhere, so the direction is named
     *   at the call site.
     */
    fun inProgress(whenUnknown: Boolean): Boolean = inProgressOrNull() ?: whenUnknown

    /** For a log line or a disarm reason. Never for a decision. */
    fun describe(): String = when (val mode = runCatching { audio?.mode }.getOrNull()) {
        null -> "audio mode unavailable (${unavailable ?: "unknown"})"
        AudioManager.MODE_IN_CALL -> "audio mode IN_CALL"
        AudioManager.MODE_IN_COMMUNICATION -> "audio mode IN_COMMUNICATION"
        AudioManager.MODE_NORMAL -> "audio mode NORMAL"
        else -> "audio mode $mode"
    }

    private companion object { const val TAG = "Molasses.CallDetector" }
}
