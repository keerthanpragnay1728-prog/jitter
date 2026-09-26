package dev.molasses.core.launch

import dev.molasses.core.launch.QuickLaunch.BuiltIn
import dev.molasses.core.launch.QuickLaunch.Entry
import dev.molasses.core.launch.QuickLaunch.Selection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuickLaunchTest {

    private val maps = "com.google.android.apps.maps"
    private val camera = "com.android.camera"

    @Test
    fun `an untouched install shows the five defaults`() {
        assertEquals(QuickLaunch.DEFAULTS, QuickLaunch.resolve(Selection(emptyList(), chosen = false)))
        assertEquals(
            listOf("@phone", "@messages", "@calendar", "@calculator", "@clock"),
            QuickLaunch.DEFAULTS.map { it.token },
        )
    }

    @Test
    fun `a chosen empty list is honoured, not replaced by the defaults`() {
        assertEquals(emptyList<Entry>(), QuickLaunch.resolve(Selection(emptyList(), chosen = true)))
    }

    @Test
    fun `a stored list is the list whatever the flag says`() {
        val stored = listOf(maps, "@messages")
        val expected = listOf(Entry.App(maps), Entry.Row(BuiltIn.MESSAGES))
        assertEquals(expected, QuickLaunch.resolve(Selection(stored, chosen = true)))
        assertEquals(expected, QuickLaunch.resolve(Selection(stored, chosen = false)))
    }

    @Test
    fun `a token this build does not know is dropped, and a package never reads as a built-in`() {
        assertNull(QuickLaunch.decode("@torch"))
        assertNull(QuickLaunch.decode("  "))
        assertEquals(Entry.App("com.example.phone"), QuickLaunch.decode("com.example.phone"))
        assertEquals(Entry.Row(BuiltIn.PHONE), QuickLaunch.decode("@phone"))
    }

    @Test
    fun `never more than five, and no duplicates`() {
        val stored = listOf(maps, maps, "@phone", "@clock", camera, "@calendar", "@calculator", "@messages")
        val resolved = QuickLaunch.resolve(Selection(stored, chosen = true))
        assertEquals(5, resolved.size)
        assertEquals(resolved.distinct(), resolved)
    }

    @Test
    fun `an uninstalled app is skipped at render and pruned at write`() {
        val sel = Selection(listOf(maps, "@messages", camera), chosen = true)
        val installed = setOf(camera)
        assertEquals(
            listOf(Entry.Row(BuiltIn.MESSAGES), Entry.App(camera)),
            QuickLaunch.visible(sel, installed::contains),
        )
        assertEquals(
            listOf("@messages", camera),
            QuickLaunch.pruned(QuickLaunch.resolve(sel), installed::contains),
        )
    }

    @Test
    fun `adding stops at five and refuses a duplicate`() {
        val full = QuickLaunch.DEFAULTS
        assertNull(QuickLaunch.added(full, Entry.App(maps)))
        val four = full.dropLast(1)
        assertEquals(four + Entry.App(maps), QuickLaunch.added(four, Entry.App(maps)))
        assertNull(QuickLaunch.added(four, four.first()))
    }

    @Test
    fun `removing the last row leaves an empty list, which stays empty`() {
        val one = listOf(Entry.App(maps))
        val none = QuickLaunch.removed(one, Entry.App(maps))
        assertEquals(emptyList<Entry>(), none)
        // Written back with the flag set, it reads as chosen-empty, not as
        // untouched.
        assertEquals(emptyList<Entry>(), QuickLaunch.resolve(Selection(QuickLaunch.pruned(none) { true }, chosen = true)))
    }

    @Test
    fun `a swap replaces the row in its slot and keeps the order`() {
        val full = QuickLaunch.DEFAULTS
        val calendar = Entry.Row(BuiltIn.CALENDAR)
        val swapped = QuickLaunch.swapped(full, calendar, Entry.App(maps))
        assertEquals(
            listOf(
                Entry.Row(BuiltIn.PHONE),
                Entry.Row(BuiltIn.MESSAGES),
                Entry.App(maps),
                Entry.Row(BuiltIn.CALCULATOR),
                Entry.Row(BuiltIn.CLOCK),
            ),
            swapped,
        )
    }

    @Test
    fun `a swap keeps the cap`() {
        val full = QuickLaunch.DEFAULTS
        var rows: List<Entry> = full
        for ((i, pkg) in listOf(maps, camera, "a.b", "c.d", "e.f").withIndex()) {
            rows = QuickLaunch.swapped(rows, rows[i], Entry.App(pkg)) ?: error("refused $pkg")
            assertEquals(QuickLaunch.MAX_SLOTS, rows.size)
        }
        assertEquals(QuickLaunch.MAX_SLOTS, QuickLaunch.resolve(Selection(QuickLaunch.pruned(rows) { true }, chosen = true)).size)
    }

    @Test
    fun `a swap to a row that is already pinned is refused`() {
        val rows = QuickLaunch.DEFAULTS.dropLast(1) + Entry.App(maps)
        assertNull(QuickLaunch.swapped(rows, Entry.Row(BuiltIn.PHONE), Entry.App(maps)))
        assertNull(QuickLaunch.swapped(rows, Entry.Row(BuiltIn.PHONE), Entry.Row(BuiltIn.MESSAGES)))
        assertNull("onto itself", QuickLaunch.swapped(rows, Entry.App(maps), Entry.App(maps)))
    }

    @Test
    fun `a swap of a row that is not there is refused`() {
        assertNull(QuickLaunch.swapped(QuickLaunch.DEFAULTS, Entry.App(maps), Entry.App(camera)))
    }
}
