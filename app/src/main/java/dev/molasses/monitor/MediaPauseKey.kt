package dev.molasses.monitor

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent

/**
 * The one media key this app sends: PAUSE, down then up. See
 * `core/safety/MediaPause` for when, and why it is never PLAY or
 * PLAY_PAUSE. No permission: `dispatchMediaKeyEvent` is open to any app,
 * and the system routes it to the most recently active media session.
 *
 * @return false when there was no AudioManager or the dispatch threw.
 */
object MediaPauseKey {

    fun send(context: Context): Boolean {
        val am = context.getSystemService(AudioManager::class.java) ?: return false
        return try {
            val now = SystemClock.uptimeMillis()
            am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE, 0))
            am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE, 0))
            true
        } catch (e: RuntimeException) {
            Log.w(TAG, "media pause key not dispatched", e)
            false
        }
    }

    private const val TAG = "Molasses.HomeFirst"
}
