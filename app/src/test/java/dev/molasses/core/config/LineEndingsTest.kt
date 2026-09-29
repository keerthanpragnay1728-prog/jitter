package dev.molasses.core.config

import dev.molasses.core.repoFile
import dev.molasses.core.repoRoot
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The working tree is LF, which is what every text-reading test assumes.
 *
 * A wiring test that matches `"clearAnswer()\n    lastKeystrokeMs ="` fails
 * on a CRLF checkout even though the code is exactly right, and its message
 * says the code regressed. That cost a round of diagnosis once. This test
 * fails first, with the actual cause and the fix, so the next CRLF checkout
 * reads as what it is.
 *
 * `.gitattributes` pins `eol=lf`, so a fresh clone is LF everywhere. A
 * checkout made before it landed keeps its CRLF files until they are checked
 * out again, which is the case this catches.
 */
class LineEndingsTest {

    private val textExtensions = setOf("kt", "kts", "xml", "md", "proto", "toml", "txt", "py", "sh", "pro", "properties")

    private val skipped = setOf("build", ".gradle", ".git", ".idea")

    private fun textFiles(): List<File> {
        val root = repoRoot()
        return root.walkTopDown()
            .onEnter { it == root || it.name !in skipped }
            .filter { it.isFile && it.extension in textExtensions }
            .toList()
    }

    @Test
    fun `no text file in the working tree has a carriage return`() {
        val files = textFiles()
        assertTrue("found no text files under ${repoRoot()}", files.size > 100)
        val crlf = files.filter { f -> f.readBytes().any { it == '\r'.code.toByte() } }
            .map { it.relativeTo(repoRoot()).path }
        assertTrue(
            "${crlf.size} text files have CR line endings, e.g. ${crlf.take(5)}. " +
                "The text-reading tests will fail on correct code. With .gitattributes " +
                "in place, commit or stash local changes, then run " +
                "`git rm -rq --cached . && git reset --hard` to check the tree out as LF.",
            crlf.isEmpty(),
        )
    }

    @Test
    fun `gitattributes pins LF`() {
        val attributes = repoFile(".gitattributes").readText()
        assertTrue(attributes.contains("* text=auto eol=lf"))
    }
}
