package dev.molasses.core.lock

import org.junit.Assert.assertEquals
import org.junit.Test

class PrefixLockTest {

    private val ig = "com.instagram.android"

    @Test
    fun `a new prefix covering a locked package is refused`() {
        val r = PrefixLock.admitted(stored = emptyList(), requested = listOf("com.instagram"), lockedPackages = listOf(ig))
        assertEquals(emptyList<String>(), r.prefixes)
        assertEquals(listOf(PrefixLock.Refusal("com.instagram", ig)), r.refused)
    }

    @Test
    fun `the exact package is refused too, whatever its spelling`() {
        val r = PrefixLock.admitted(emptyList(), listOf("  COM.Instagram.Android "), listOf(ig))
        assertEquals(listOf(PrefixLock.Refusal("com.instagram.android", ig)), r.refused)
    }

    @Test
    fun `a prefix that only shares characters is not a match and is kept`() {
        // com.insta does not cover com.instagram.android: prefixes match on
        // a dot boundary, the same rule SensitivePackages applies.
        val r = PrefixLock.admitted(emptyList(), listOf("com.insta"), listOf(ig))
        assertEquals(listOf("com.insta"), r.prefixes)
        assertEquals(emptyList<PrefixLock.Refusal>(), r.refused)
    }

    @Test
    fun `other entries in the same edit are kept`() {
        val r = PrefixLock.admitted(emptyList(), listOf("com.mybank", "com.instagram"), listOf(ig))
        assertEquals(listOf("com.mybank"), r.prefixes)
        assertEquals(listOf(PrefixLock.Refusal("com.instagram", ig)), r.refused)
    }

    @Test
    fun `a prefix already stored stays even if it now covers a lock`() {
        val r = PrefixLock.admitted(listOf("com.instagram"), listOf("com.instagram", "com.mybank"), listOf(ig))
        assertEquals(listOf("com.instagram", "com.mybank"), r.prefixes)
        assertEquals(emptyList<PrefixLock.Refusal>(), r.refused)
    }

    @Test
    fun `with no lock standing anything is admitted`() {
        val r = PrefixLock.admitted(emptyList(), listOf("com.instagram"), emptyList())
        assertEquals(listOf("com.instagram"), r.prefixes)
    }

    @Test
    fun `removing a prefix is never refused`() {
        val r = PrefixLock.admitted(listOf("com.instagram", "com.mybank"), listOf("com.mybank"), listOf(ig))
        assertEquals(listOf("com.mybank"), r.prefixes)
    }

    @Test
    fun `blank entries are dropped, not refused`() {
        val r = PrefixLock.admitted(emptyList(), listOf("", "  "), listOf(ig))
        assertEquals(emptyList<String>(), r.prefixes)
        assertEquals(emptyList<PrefixLock.Refusal>(), r.refused)
    }

    @Test
    fun `a prefix covering two locked packages names both`() {
        val yt = "com.google.android.youtube"
        val music = "com.google.android.apps.youtube.music"
        val r = PrefixLock.admitted(emptyList(), listOf("com.google.android"), listOf(yt, music, ig))
        assertEquals(
            listOf(PrefixLock.Refusal("com.google.android", yt), PrefixLock.Refusal("com.google.android", music)),
            r.refused,
        )
        assertEquals(emptyList<String>(), r.prefixes)
    }
}
