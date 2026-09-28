package dev.molasses.overlay

import android.content.Context
import android.media.AudioManager
import android.util.Log
import dev.molasses.core.safety.MuteGuard
import dev.molasses.core.safety.OverlayAudio

/**
 * The music-stream mute, applied and given back as [MuteGuard] decides.
 *
 * One object for the process, because the stream is one stream: the three
 * full-screen overlays each hold an [AudioFocusHold], and each of those takes
 * and releases through here under its own holder id, so a handover from one
 * overlay to the next never unmutes under the one still up.
 *
 * ## The flag on disk
 * "Jitter muted it" is persisted with a synchronous SharedPreferences commit,
 * written before the mute and cleared after the unmute. So there is no
 * instant at which the stream is muted by us and the disk does not say so; a
 * process death anywhere in between is repaired by [restoreOnConnect]. Not
 * DataStore: its writes are asynchronous, and this flag's whole job is to be
 * on disk before the mute it describes. It is read at exactly one place,
 * service connect, and written only here.
 *
 * ## What it never does
 * Change the volume level (`ADJUST_MUTE` and `ADJUST_UNMUTE` touch the mute
 * flag only), show volume UI (flags 0), unmute a stream it did not mute, or
 * mute at entry. A `SecurityException` from the platform is logged and the
 * overlay carries on unmuted.
 *
 * Main thread only: every caller is an overlay manager's show or its choke
 * point, and the service's connect.
 */
object StreamMute {

    private const val TAG = "Molasses.AudioFocus"
    private const val PREFS = "molasses_audio"
    private const val KEY_OURS = "music_muted_by_jitter"
    private const val STREAM = AudioManager.STREAM_MUSIC

    private var state = MuteGuard.State()

    fun take(context: Context, holder: String, overlay: OverlayAudio.Overlay) {
        val am = context.getSystemService(AudioManager::class.java) ?: return
        val alreadyMuted = runCatching { am.isStreamMute(STREAM) }.getOrDefault(false)
        val step = MuteGuard.take(state, holder, OverlayAudio.silences(overlay), alreadyMuted)
        state = step.state
        var applied = "no"
        if (step.action == MuteGuard.Action.MUTE) {
            // On disk before the mute. See the class doc.
            persist(context, ours = true)
            val result = runCatching { am.adjustStreamVolume(STREAM, AudioManager.ADJUST_MUTE, 0) }
            if (result.isSuccess) {
                applied = "yes"
            } else {
                Log.w(TAG, "mute refused for $overlay; carrying on unmuted", result.exceptionOrNull())
                state = MuteGuard.muteFailed(state)
                persist(context, ours = false)
                applied = "failed"
            }
        }
        Log.i(TAG, "mute: overlay=$overlay holder=$holder alreadyMuted=$alreadyMuted applied=$applied ours=${state.ours}")
    }

    fun release(context: Context, holder: String) {
        val held = holder in state.holders
        val step = MuteGuard.release(state, holder)
        state = step.state
        val restored = step.action == MuteGuard.Action.UNMUTE && unmute(context)
        // Only for a holder that took a silencing hold; an entry overlay
        // never held one and has nothing to report.
        if (held) Log.i(TAG, "mute: holder=$holder released, restore performed=$restored ours=${state.ours}")
    }

    /**
     * Service connect: a flag still on disk means a process died with our
     * mute in force. Give the stream back and clear it, before any overlay
     * can go up.
     */
    fun restoreOnConnect(context: Context) {
        state = MuteGuard.State()
        val ours = prefs(context).getBoolean(KEY_OURS, false)
        val restored = MuteGuard.onConnect(ours) == MuteGuard.Action.UNMUTE && unmute(context)
        Log.i(TAG, "mute: service connect, flag=$ours restore performed=$restored")
    }

    /** Unmute, then clear the flag. A death between the two unmutes again next connect, which is harmless. */
    private fun unmute(context: Context): Boolean {
        val am = context.getSystemService(AudioManager::class.java)
        val done = am != null && runCatching { am.adjustStreamVolume(STREAM, AudioManager.ADJUST_UNMUTE, 0) }
            .onFailure { Log.w(TAG, "unmute refused", it) }
            .isSuccess
        persist(context, ours = false)
        return done
    }

    private fun persist(context: Context, ours: Boolean) {
        prefs(context).edit().putBoolean(KEY_OURS, ours).commit()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
