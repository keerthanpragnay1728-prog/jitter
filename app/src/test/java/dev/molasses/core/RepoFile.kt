package dev.molasses.core

import java.io.File

/**
 * Find a file by its path from the repository root, from any working directory.
 *
 * ## Why this exists
 * Several tests in this repository assert things about sources rather than
 * about behaviour, because the file they care about is one of the ones no tool
 * in this environment compiles. `FontScaleWiringTest` reads five call sites,
 * `AccessibilityConfigTest` reads the manifest, `AudioFocusWiringTest` reads
 * where two overlays take and release audio focus.
 *
 * Each of them needs the same six lines, and each of them had its own copy.
 * Twelve copies, and the two spellings had already drifted: some threw
 * `AssertionError`, some called `error()`, and the messages disagreed about
 * whether the search was "from" or "above" the working directory.
 *
 * ## Why the walk, rather than a relative path
 * The suite runs from two working directories. Gradle runs it from the
 * repository root and `tools/pure-verify` runs it from its own directory, so
 * no fixed relative path is correct in both. Walking up until the path
 * resolves is correct in both and in any third one.
 *
 * Deliberately not a test of anything. It is the one piece of machinery those
 * tests share, and a shared helper that can fail in two different ways is the
 * same problem as a guard with two copies.
 */
fun repoFile(relative: String): File {
    var dir: File? = File(System.getProperty("user.dir")!!).absoluteFile
    while (dir != null) {
        val candidate = File(dir, relative)
        if (candidate.isFile) return candidate
        dir = dir.parentFile
    }
    throw AssertionError(
        "could not find $relative in any directory above ${System.getProperty("user.dir")}",
    )
}
