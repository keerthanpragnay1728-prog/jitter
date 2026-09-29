package dev.molasses.core

import java.io.File

/**
 * The path, from the repository root, of the file that identifies the root.
 *
 * The app manifest rather than a settings file, because `tools/pure-verify`
 * has a `settings.gradle.kts` of its own and a walk that stopped at the first
 * one would stop inside the harness.
 */
const val REPO_ROOT_MARKER = "app/src/main/AndroidManifest.xml"

/**
 * The repository root, found by walking up from the working directory to
 * [REPO_ROOT_MARKER]. Throws when there is none.
 *
 * ## Why it throws rather than letting the caller skip
 * `PurityTest` and `DebugSurfaceTest` used to look in `.`, `..` and a
 * hardcoded checkout path, and skip through `assumeTrue` when none matched.
 * The suite runs from `tools/pure-verify`, where neither relative path is the
 * root, so outside that one checkout path both of them skipped every time and
 * the report still said green. A guard that skips where it cannot see is the
 * guard with no caller in CLAUDE.md, one layer down: it reads as coverage
 * and is none. Failing is the only answer that cannot be mistaken for a pass.
 *
 * ## Why the walk, rather than a relative path
 * The suite runs from two working directories. Gradle runs it from the
 * repository root and `tools/pure-verify` runs it from its own directory, so
 * no fixed relative path is correct in both. Walking up is correct in both and
 * in any third one.
 */
fun repoRoot(from: File = File(System.getProperty("user.dir")!!)): File {
    val start = from.absoluteFile
    var dir: File? = start
    while (dir != null) {
        if (File(dir, REPO_ROOT_MARKER).isFile) return dir
        dir = dir.parentFile
    }
    throw AssertionError("could not find $REPO_ROOT_MARKER in any directory above $start")
}

/**
 * Find a file by its path from the repository root, from any working directory.
 *
 * Several tests in this repository assert things about sources rather than
 * about behaviour, because the file they care about is one of the ones no tool
 * in this environment compiles. They share this one helper so a missing file
 * fails the same way everywhere.
 */
fun repoFile(relative: String): File {
    val candidate = File(repoRoot(), relative)
    if (!candidate.isFile) throw AssertionError("$relative does not exist under ${candidate.parentFile}")
    return candidate
}
