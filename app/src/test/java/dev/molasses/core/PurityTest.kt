package dev.molasses.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The brief requires `FrictionEngine` to be "pure, testable, no Android
 * imports". This turns that from a claim into a check: it walks the source
 * tree and fails if anything in the pure set imports `android.*` or
 * `androidx.*`.
 *
 * It matters beyond tidiness. The pure set is the only part of this app that
 * can be compiled and tested without the Android SDK, which in a
 * network-restricted build environment is the difference between verified
 * logic and unverified logic.
 */
class PurityTest {

    /**
     * Whole packages that are pure by contract. `core` is listed as a single
     * root rather than package-by-package so a new subpackage is covered the
     * moment it is created, instead of when someone remembers to add it here.
     */
    private val pureRoots = listOf("core", "engine")

    /** Individual pure files inside otherwise-Android packages. */
    private val pureFiles = listOf(
        "sensing/CadenceAnalyzer.kt",
        "sensing/StepGate.kt",
        "sensing/FallbackImuGate.kt",
        "sensing/GravitySplitter.kt",
        "sensing/Thresholds.kt",
        "sensing/HysteresisGate.kt",
        "sensing/SustainAccumulator.kt",
        "sensing/GateEvaluation.kt",
        "sensing/TickEvaluation.kt",
    )

    /**
     * The one `java.*` import the pure set is allowed, named by file and by
     * type.
     *
     * `java.time.LocalDate` is arithmetic on three integers: no I/O, no
     * locale, no timezone. That is what `DateMath` needs and it is the only
     * reason a `java.*` import is here at all. Hand-rolling days-from-civil
     * to protect the convention would put the century leap rule in our own
     * hands, where an off-by-one hides for a year.
     *
     * It is written as this file may import exactly this type, rather than
     * as an exception for `java.time`, on purpose. A general exception is a
     * precedent the next import inherits without arguing, and the ones next
     * door are the ones the rule exists to keep out: `LocalDateTime`,
     * `ZonedDateTime`, `Instant` and `Duration` all carry a timezone or a
     * clock. The narrow form makes the next `java.*` import come back here
     * and make its own case.
     */
    private val javaAllowance: Map<String, Set<String>> = mapOf(
        "DateMath.kt" to setOf("java.time.LocalDate"),
    )

    /**
     * Found by [repoRoot], which fails rather than skips. This test used to
     * look in `.`, `..` and a hardcoded checkout path and skip when none
     * matched, which from `tools/pure-verify` was every checkout but one.
     */
    private fun sourceRoot(): File = File(repoRoot(), "app/src/main/java/dev/molasses")

    /** Every file in the pure set, as the two lists above describe it. */
    private fun pureSources(root: File): List<File> = buildList {
        pureRoots.forEach { r ->
            File(root, r).walkTopDown().filter { it.extension == "kt" }.forEach { add(it) }
        }
        pureFiles.forEach { add(File(root, it)) }
    }

    @Test
    fun `the pure set imports nothing from android`() {
        val root = sourceRoot()
        val offenders = mutableListOf<String>()
        val files = pureSources(root)

        assertTrue("expected to find pure sources, found none under $root", files.isNotEmpty())

        for (f in files) {
            if (!f.isFile) continue
            f.readLines().forEachIndexed { i, line ->
                val t = line.trim()
                if (t.startsWith("import android.") || t.startsWith("import androidx.")) {
                    offenders += "${f.name}:${i + 1}: $t"
                }
            }
        }

        assertTrue(
            "pure set must not import Android:\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun `the verification harness compiles exactly the set this test checks`() {
        // Two lists describing "the pure set" is one list too many. If the
        // harness compiles something this test does not check, an Android
        // import could land in it unnoticed; if this test checks something the
        // harness does not compile, the guarantee is theoretical.
        val build = repoFile("tools/pure-verify/build.gradle.kts")

        val block = build.readText()
            .substringAfter("val pureMain = listOf(")
            .substringBefore(")")
        val harnessEntries = Regex("\"([^\"]+)\"").findAll(block)
            .map { it.groupValues[1].removePrefix("dev/molasses/") }
            .toSet()

        val checked = (pureRoots + pureFiles).toSet()
        assertEquals(
            "harness pureMain and PurityTest disagree about what the pure set is",
            checked, harnessEntries,
        )
    }

    @Test
    fun `the pure set imports nothing from java but the one allowance`() {
        val offenders = mutableListOf<String>()
        for (f in pureSources(sourceRoot())) {
            if (!f.isFile) continue
            val allowed = javaAllowance[f.name].orEmpty()
            f.readLines().forEachIndexed { i, line ->
                val t = line.trim()
                if (!t.startsWith("import java.")) return@forEachIndexed
                val type = t.removePrefix("import ").substringBefore(" as ").trim()
                if (type !in allowed) offenders += "${f.name}:${i + 1}: $t"
            }
        }

        assertTrue(
            "pure set may import java.* only where javaAllowance says so:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }
}
