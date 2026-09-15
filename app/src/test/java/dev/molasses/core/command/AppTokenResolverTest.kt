package dev.molasses.core.command

import dev.molasses.core.command.AppTokenResolver.Candidate
import dev.molasses.core.command.AppTokenResolver.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppTokenResolverTest {

    private val ig = Candidate("com.instagram.android", "Instagram")
    private val yt = Candidate("com.google.android.youtube", "YouTube")
    private val photos = Candidate("com.google.android.apps.photos", "Photos")
    private val shop = Candidate("com.adobe.photoshop", "Photoshop")
    private val all = listOf(ig, yt, photos, shop)
    private val targets = setOf(ig.pkg, yt.pkg)

    private fun resolve(token: String) = AppTokenResolver.resolve(token, all, targets)

    @Test
    fun `an exact package name resolves`() {
        assertEquals(Result.One(ig.pkg), resolve("com.instagram.android"))
    }

    @Test
    fun `an exact label resolves, case insensitively`() {
        assertEquals(Result.One(yt.pkg), resolve("YouTube"))
        assertEquals(Result.One(yt.pkg), resolve("youtube"))
    }

    @Test
    fun `a prefix of a label resolves`() {
        assertEquals(Result.One(ig.pkg), resolve("insta"))
    }

    @Test
    fun `an exact label beats a longer one that contains it`() {
        // "photos" must not lose to "photoshop", which contains it.
        assertEquals(Result.One(photos.pkg), resolve("photos"))
    }

    @Test
    fun `an ambiguous token is refused and names what it matched`() {
        // The failure this prevents: a thirty day lock on the wrong app, with
        // no unlock, because a two letter token silently picked the first
        // match.
        val r = resolve("photo")
        assertTrue(r.toString(), r is Result.Ambiguous)
        assertEquals(
            listOf(photos.pkg, shop.pkg).sorted(),
            (r as Result.Ambiguous).packages.sorted(),
        )
    }

    @Test
    fun `a tracked app wins over an untracked one`() {
        // "go" matches Google's packages and YouTube's. YouTube is tracked, so
        // it is the one the user almost certainly means, and the only one
        // where a lock does anything.
        val r = AppTokenResolver.resolve("google.android", all, targets)
        assertEquals(Result.One(yt.pkg), r)
    }

    @Test
    fun `an unknown token resolves to nothing`() {
        assertEquals(Result.None, resolve("mastodon"))
    }

    @Test
    fun `an empty token resolves to nothing rather than everything`() {
        assertEquals(Result.None, resolve(""))
        assertEquals(Result.None, resolve("   "))
    }

    @Test
    fun `an empty candidate list resolves to nothing`() {
        assertEquals(Result.None, AppTokenResolver.resolve("insta", emptyList(), targets))
    }

    @Test
    fun `with no preferred set it still resolves an unambiguous token`() {
        assertEquals(Result.One(ig.pkg), AppTokenResolver.resolve("insta", all))
    }
}
