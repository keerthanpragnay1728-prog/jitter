package dev.molasses.overlay

import android.content.Context
import android.media.AudioManager

/**
 * "Is the user on a call right now."
 *
 * ## Why `AudioManager.getMode()` and not telephony
 * It needs no grant, and it catches VoIP, which
 * `TelephonyCallback.CallStateListener` misses entirely. `READ_PHONE_STATE` is
 * deliberately not requested by this app, so the telephony callback is a
 * secondary that is usually unavailable; this is the primary and it always
 * works.
 *
 * `MODE_IN_COMMUNICATION` is also set by some assistant and voice recording
 * flows, so this occasionally reports a call with none in progress. That is
 * the correct direction to fail. Every caller uses it to decide whether to
 * *stop* doing something, so a false positive costs a stall or a gate and a
 * false negative costs someone a phone call.
 *
 * ## Why it is a class and not two lines at each call site
 * It was two lines inside the shutter, and the launch gate needs the same
 * answer for the same reason. A safety check with two copies is a safety check
 * with two behaviours as soon as one of them is edited.
 */
class CallDetector(context: Context) {

    private val audio: AudioManager? =
        context.applicationContext.getSystemService(AudioManager::class.java)

    fun inProgress(): Boolean {
        val mode = audio?.mode ?: return false
        return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
    }
}
