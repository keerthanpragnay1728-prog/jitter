package dev.molasses.core

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
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

    private val pureRoots = listOf(
        "core/model",
        "core/time",
        "engine",
    )

    private val pureFiles = listOf(
        "sensing/CadenceAnalyzer.kt",
        "sensing/StepGate.kt",
        "sensing/FallbackImuGate.kt",
    )

    private fun sourceRoot(): File? = listOf(
        File("app/src/main/java/dev/molasses"),
        File("../app/src/main/java/dev/molasses"),
        File("/home/user/visceral/app/src/main/java/dev/molasses"),
    ).firstOrNull { it.isDirectory }

    @Test
    fun `the pure set imports nothing from android`() {
        val root = sourceRoot()
        // Skip rather than fail when run from a working directory that cannot
        // see the sources; the compile-time guarantee still holds.
        assumeTrue("source tree not locatable from ${File("").absolutePath}", root != null)

        val offenders = mutableListOf<String>()
        val files = buildList {
            pureRoots.forEach { r ->
                File(root, r).walkTopDown().filter { it.extension == "kt" }.forEach { add(it) }
            }
            pureFiles.forEach { add(File(root, it)) }
        }

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
}
