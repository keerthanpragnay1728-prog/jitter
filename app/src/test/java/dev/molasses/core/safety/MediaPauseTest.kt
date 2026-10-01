package dev.molasses.core.safety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaPauseTest {

    private val pausing = setOf(OverlayKind.EXPIRED_GATE, OverlayKind.WALK_GATE)

    @Test
    fun `for a video app, only the expired and walking gates pause`() {
        for (kind in OverlayKind.entries) {
            assertEquals("$kind, video app", kind in pausing, MediaPause.sendsPause(kind, isVideoApp = true))
        }
    }

    @Test
    fun `for a non-video app, no overlay kind pauses`() {
        for (kind in OverlayKind.entries) {
            assertFalse("$kind, non-video app", MediaPause.sendsPause(kind, isVideoApp = false))
        }
    }

    @Test
    fun `YouTube is a video app by name, with no category`() {
        assertTrue(MediaPause.isVideoApp("com.google.android.youtube", categoryVideo = false))
        assertTrue(MediaPause.sendsPause(OverlayKind.EXPIRED_GATE, MediaPause.isVideoApp("com.google.android.youtube", false)))
    }

    @Test
    fun `an app declaring CATEGORY_VIDEO is a video app`() {
        assertTrue(MediaPause.isVideoApp("org.example.tube", categoryVideo = true))
    }

    @Test
    fun `a non-video app never pauses, on any overlay`() {
        for (pkg in listOf("com.twitter.android", "com.linkedin.android", "com.facebook.katana", "com.instagram.android")) {
            val video = MediaPause.isVideoApp(pkg, categoryVideo = false)
            assertFalse("$pkg is not a video app without the category", video)
            for (kind in OverlayKind.entries) assertFalse("$pkg, $kind", MediaPause.sendsPause(kind, video))
        }
    }

    @Test
    fun `YouTube Music is not YouTube`() {
        // By exact name only. A prefix would take in YouTube Music, whose
        // session is the user's own music.
        assertFalse(MediaPause.isVideoApp("com.google.android.apps.youtube.music", categoryVideo = false))
    }
}
