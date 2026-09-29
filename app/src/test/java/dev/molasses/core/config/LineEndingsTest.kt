package dev.molasses.core.config

import dev.molasses.core.repoFile
import dev.molasses.core.repoRoot
import java.io.File
import java.io.IOException
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every tracked text file is LF in the working tree, which is what every
 * text-reading test assumes.
 *
 * A wiring test that matches `"clearAnswer()\n    lastKeystrokeMs ="` fails
 * on a CRLF checkout even though the code is exactly right, and its message
 * says the code regressed. That cost a round of diagnosis once. This test
 * fails first, with the actual cause and the fix, so the next CRLF checkout
 * reads as what it is.
 *
 * ## Tracked files only, and git decides which are text
 * `.gitattributes` governs what git tracks and nothing else. An untracked
 * `local.properties` from Android Studio, or a diagnostic dump next to the
 * sources, is outside its reach, so a directory walk failed the owner's
 * machine on five files this repository never sees. The list comes from
 * `git ls-files`, and a file is skipped only when `git check-attr` says its
 * `text` attribute is unset (`-text`, which `binary` also sets). There is no
 * list of names here to drift.
 *
 * Without git there is nothing to check against, and an empty list must not
 * read as a pass, so both fail loudly.
 */
class LineEndingsTest {

    /** Runs git at the repository root and returns stdout, or fails the test. */
    private fun git(vararg args: String, stdin: ByteArray? = null): ByteArray {
        val process = try {
            ProcessBuilder(listOf("git") + args).directory(repoRoot()).start()
        } catch (e: IOException) {
            throw AssertionError(
                "git is not available (${e.message}). This test reads the tracked " +
                    "file list from git and cannot check line endings without it.",
            )
        }
        // Written from its own thread: check-attr answers while it reads, and
        // writing the whole list before reading could fill both pipes.
        val writer = Thread { process.outputStream.use { if (stdin != null) it.write(stdin) } }
        writer.start()
        val out = process.inputStream.readBytes()
        writer.join()
        val err = process.errorStream.readBytes().toString(Charsets.UTF_8)
        val code = process.waitFor()
        if (code != 0) throw AssertionError("git ${args.joinToString(" ")} exited $code: $err")
        return out
    }

    /** NUL separated, so a path with a space or a newline survives. */
    private fun ByteArray.nulFields(): List<String> =
        toString(Charsets.UTF_8).split('\u0000').filter { it.isNotEmpty() }

    /** Tracked paths whose `text` attribute is not unset. */
    private fun trackedTextPaths(): List<String> {
        val tracked = git("ls-files", "-z").nulFields()
        assertTrue("git ls-files listed no files under ${repoRoot()}", tracked.isNotEmpty())

        // check-attr -z prints path, attribute, value for each path.
        val attrs = git(
            "check-attr", "-z", "--stdin", "text",
            stdin = tracked.joinToString("\u0000", postfix = "\u0000").toByteArray(Charsets.UTF_8),
        ).nulFields()
        assertTrue("check-attr answered for ${attrs.size / 3} of ${tracked.size} files", attrs.size == tracked.size * 3)
        return attrs.chunked(3).filter { (_, _, value) -> value != "unset" }.map { it[0] }
    }

    @Test
    fun `no tracked text file in the working tree has a carriage return`() {
        val paths = trackedTextPaths()
        assertTrue("no tracked text files to check", paths.isNotEmpty())
        val root = repoRoot()
        // A tracked file deleted in the working tree has no line endings to
        // check; it is git status's business, not this test's.
        val present = paths.map { File(root, it) }.filter { it.isFile }
        assertTrue("none of the ${paths.size} tracked text files exist under $root", present.isNotEmpty())
        val crlf = present.filter { f -> f.readBytes().any { it == '\r'.code.toByte() } }
            .map { it.relativeTo(root).path }
        assertTrue(
            "${crlf.size} tracked text files have CR line endings, e.g. ${crlf.take(5)}. " +
                "The text-reading tests will fail on correct code. With .gitattributes " +
                "in place, commit or stash local changes, then run " +
                "`git rm -rq --cached . && git reset --hard` to check the tree out as LF.",
            crlf.isEmpty(),
        )
    }

    @Test
    fun `gitattributes pins LF and leaves the batch files alone`() {
        val attributes = repoFile(".gitattributes").readText()
        assertTrue(attributes.contains("* text=auto eol=lf"))
        // The skip is read from git, so check git reads it the way the file
        // intends: the wrappers are -text and ordinary sources are not.
        val answers = git("check-attr", "-z", "text", "--", "gradlew.bat", "README.md").nulFields().chunked(3)
            .associate { it[0] to it[2] }
        assertTrue("gradlew.bat should be -text, git says ${answers["gradlew.bat"]}", answers["gradlew.bat"] == "unset")
        assertTrue("README.md should be text, git says ${answers["README.md"]}", answers["README.md"] == "auto")
    }
}
