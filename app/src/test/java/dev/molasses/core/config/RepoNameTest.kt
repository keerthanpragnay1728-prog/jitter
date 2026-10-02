package dev.molasses.core.config

import dev.molasses.core.repoRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * The repository was renamed to keerthanpragnay1728-prog/jitter (the old
 * name is [oldRepo], spelled in parts below). GitHub redirects the old name for now,
 * but a redirect is a courtesy that ends the moment anything else takes the
 * name, and the report row, the download link and the checksum steps would
 * then point at someone else's repository.
 *
 * So no tracked file names the old repository, except the README under
 * DESIGN HISTORY, which records the past as it was.
 */
class RepoNameTest {

    /** Built from parts so this file does not name it and fail itself. */
    private val oldRepo = "keerthanpragnay1728-prog/" + "visceral"

    private fun trackedPaths(): List<String> {
        val process = try {
            ProcessBuilder("git", "ls-files", "-z").directory(repoRoot()).start()
        } catch (e: IOException) {
            throw AssertionError("git is not available (${e.message}); this test reads the tracked file list from git.")
        }
        val out = process.inputStream.readBytes()
        val code = process.waitFor()
        if (code != 0) throw AssertionError("git ls-files exited $code")
        return out.toString(Charsets.UTF_8).split('\u0000').filter { it.isNotEmpty() }
    }

    @Test
    fun `no tracked file names the old repository, outside DESIGN HISTORY`() {
        val paths = trackedPaths()
        assertTrue("git ls-files listed no files", paths.isNotEmpty())
        val needle = oldRepo.toByteArray(Charsets.US_ASCII)
        val offenders = paths.mapNotNull { path ->
            val file = File(repoRoot(), path)
            if (!file.isFile) return@mapNotNull null
            var bytes = file.readBytes()
            if (path == "README.md") {
                val text = bytes.toString(Charsets.UTF_8)
                val history = text.indexOf("\n## DESIGN HISTORY\n")
                assertTrue("README has no DESIGN HISTORY heading", history >= 0)
                bytes = text.substring(0, history).toByteArray(Charsets.UTF_8)
            }
            path.takeIf { contains(bytes, needle) }
        }
        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun `the report row's URL is the new repository`() {
        assertEquals(
            "https://github.com/keerthanpragnay1728-prog/jitter/issues",
            dev.molasses.core.support.ReportProblem.ISSUES_URL,
        )
    }

    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }
}
