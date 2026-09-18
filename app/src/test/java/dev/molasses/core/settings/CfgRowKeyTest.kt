package dev.molasses.core.settings

import dev.molasses.core.settings.CfgAccordion.Section
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The keys have one job and it is invisible when they fail at it.
 *
 * A duplicate key is a crash inside `LazyColumn`. A key that the rail builds
 * differently from the one the renderer handed Compose is not even that: the
 * lookup returns -1, the rail declines to scroll, and one letter quietly does
 * nothing. So both properties are asserted rather than reasoned about.
 */
class CfgRowKeyTest {

    private val packages = listOf(
        "com.instagram.android",
        "com.google.android.youtube",
        "com.twitter.android",
        "com.whatsapp",
        // The awkward ones. A package may not legally contain a colon, but a
        // label-derived key would, so this is the shape the scheme has to
        // survive if anyone widens it later.
        "a",
        "com.a.b.c.d.e.f",
    )

    private fun everyKey(): List<String> =
        Section.entries.map { CfgRowKey.section(it) } +
            Section.entries.flatMap { s ->
                listOf("title", "body", "hint", "count").map { CfgRowKey.body(s, it) }
            } +
            packages.map { CfgRowKey.app(it) } +
            listOf("tracked", "untracked").map { CfgRowKey.group(it) } +
            listOf(10L, 15L, 18L, 20L, 25L, 30L, 40L, 50L, 60L)
                .map { CfgRowKey.horizon(it * 60_000L) }

    @Test
    fun `no two rows in one screenful share a key`() {
        // The crash case, swept over every section, a realistic set of body
        // ids, every package and every horizon on the ladder at once. That is
        // more rows than CFG will ever render, which is the point.
        val keys = everyKey()
        assertEquals(keys.size, keys.distinct().size)
    }

    @Test
    fun `a body id can repeat across sections without colliding`() {
        // "title" is the obvious local id and it will be written in more than
        // one section. The section is in the key so that is safe.
        assertTrue(CfgRowKey.body(Section.SETUP, "title") != CfgRowKey.body(Section.GATE, "title"))
    }

    @Test
    fun `a section header and its body rows are different keys`() {
        for (section in Section.entries) {
            assertTrue(CfgRowKey.section(section) != CfgRowKey.body(section, "title"))
        }
    }

    @Test
    fun `no prefix is a prefix of another`() {
        // "app" and "apps" would be a real bug and an easy one to write. The
        // separator makes it safe in practice; this asserts the property the
        // separator is relied on for.
        for (a in CfgRowKey.PREFIXES) {
            for (b in CfgRowKey.PREFIXES) {
                if (a === b) continue
                assertTrue("$a and $b collide", !a.startsWith(b) && !b.startsWith(a))
            }
        }
    }

    @Test
    fun `every key starts with a declared prefix`() {
        // PREFIXES is only worth anything if it is complete. This is the check
        // that fails when a sixth kind of row is added and not listed.
        for (key in everyKey()) {
            assertTrue(
                "$key has no declared prefix",
                CfgRowKey.PREFIXES.any { key.startsWith("$it:") },
            )
        }
    }

    @Test
    fun `an app key is derived from the package and nothing else`() {
        // The rail builds this key from a package it got out of the grouping,
        // and the renderer builds it from the package it is rendering. They
        // are the same call, which is the whole point of the file.
        assertEquals(CfgRowKey.app("com.instagram.android"), CfgRowKey.app("com.instagram.android"))
        assertTrue(CfgRowKey.app("com.a") != CfgRowKey.app("com.b"))
    }

    @Test
    fun `every grouped row kind gets a distinct key through the one entry point`() {
        // The renderer calls of() and nothing else, so a new Row case is a
        // compile error here rather than a row that silently shares a key
        // with its neighbour.
        val insta = TargetGrouping.Entry("com.instagram.android", "Instagram")
        val rows = listOf(
            TargetGrouping.Row.TrackedHeader,
            TargetGrouping.Row.HorizonHeader(15 * 60_000L),
            TargetGrouping.Row.App(insta, tracked = true, horizonMs = 15 * 60_000L),
            TargetGrouping.Row.UntrackedHeader,
            TargetGrouping.Row.HorizonHeader(60 * 60_000L),
        )
        val keys = rows.map { CfgRowKey.of(it) }
        assertEquals(keys.size, keys.distinct().size)
    }

    @Test
    fun `the rail and the renderer derive the same key for an app`() {
        // The mismatch this file exists to prevent. The renderer goes through
        // of() with a Row; the rail goes through app() with a package it took
        // out of the grouping. Both have to land on the same string.
        val entry = TargetGrouping.Entry("com.instagram.android", "Instagram")
        val row = TargetGrouping.Row.App(entry, tracked = false, horizonMs = null)
        assertEquals(CfgRowKey.app(entry.pkg), CfgRowKey.of(row))
    }

    @Test
    fun `keys are stable across calls, so nothing is generated per recomposition`() {
        // A key that changed between recompositions would defeat every reuse
        // LazyColumn does, and would look like a performance problem rather
        // than a correctness one.
        repeat(3) {
            assertEquals(CfgRowKey.section(Section.TARGETS), CfgRowKey.section(Section.TARGETS))
            assertEquals(CfgRowKey.horizon(25 * 60_000L), CfgRowKey.horizon(25 * 60_000L))
        }
    }
}
