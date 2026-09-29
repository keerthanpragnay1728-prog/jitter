package dev.molasses.core.config

import dev.molasses.core.repoFile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WorkManager is not a dependency.
 *
 * It was declared and never used. Its library manifest merges an
 * initialisation provider and its own services into the app, and depending
 * on version a FOREGROUND_SERVICE permission, which would contradict the
 * app's stated profile of no foreground service of any type. That merge
 * happens at build time and cannot be seen from source, so this pins the
 * one thing that can be: nothing asks for the library.
 */
class NoWorkManagerTest {

    @Test
    fun `the build and the catalog do not declare work-runtime`() {
        val build = repoFile("app/build.gradle.kts").readText()
        val catalog = repoFile("gradle/libs.versions.toml").readText()
        assertFalse(build.contains("work.runtime"))
        assertFalse(catalog.contains("androidx.work"))
    }

    @Test
    fun `no main source imports androidx dot work`() {
        // app/src/main only: this file names the package in its own asserts.
        // parentFile is nullable. A null here would mean the manifest path has
        // no directory at all, which is a broken checkout, so it fails loudly
        // rather than walking nothing and passing.
        val src = repoFile("app/src/main/AndroidManifest.xml").parentFile
            ?: throw AssertionError("AndroidManifest.xml has no parent directory")
        val sources = src.walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue("walked no Kotlin sources under $src", sources.isNotEmpty())
        val offenders = sources.filter { it.readText().contains("androidx.work") }.map { it.name }
        assertTrue("androidx.work referenced in $offenders", offenders.isEmpty())
    }
}
