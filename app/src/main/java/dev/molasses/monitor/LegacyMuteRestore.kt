package dev.molasses.monitor

import android.content.Context
import android.media.AudioManager
import android.util.Log

/**
 * Gives the music stream back if the previous build left it muted.
 *
 * ## Expiring: delete in the release after this one
 * The build before this muted STREAM_MUSIC under the silencing overlays and
 * persisted a "Jitter muted it" flag so a process death could not leave the
 * phone silent. The mute is gone (the Bluetooth absolute-volume sync undid
 * it within seconds on device, and home-first replaced it; see `OverlayKind`),
 * but a tester who updates with that flag set would have a stream still
 * muted by us and nothing left that would ever unmute it. So on service
 * connect this reads the flag, unmutes if it is set, and clears it.
 *
 * Nothing writes the flag any more, so once every tester has connected on
 * this build it can never be set again. That is the expiry: remove this file
 * and its call in `onServiceConnected` in the next release. CLAUDE.md, "a
 * guard with no caller", case 2: kept for exactly one release because
 * something already in the field can still reach it.
 */
object LegacyMuteRestore {

    private const val TAG = "Molasses.HomeFirst"

    /** The file and key the previous build wrote. Must not change. */
    private const val PREFS = "molasses_audio"
    private const val KEY_OURS = "music_muted_by_jitter"

    fun restoreOnConnect(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_OURS, false)) return
        val am = context.getSystemService(AudioManager::class.java)
        val unmuted = am != null && runCatching {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
        }.onFailure { Log.w(TAG, "legacy unmute refused", it) }.isSuccess
        prefs.edit().remove(KEY_OURS).commit()
        Log.i(TAG, "legacy mute flag found on connect; unmuted=$unmuted, flag cleared")
    }
}
