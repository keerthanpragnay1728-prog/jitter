package dev.molasses.core.config

import dev.molasses.core.repoFile
import dev.molasses.core.repoRoot
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * README shows no broken image, and names no screenshot nobody was asked for.
 *
 * An image line outside a comment must point at a file that exists. One kept
 * inside an HTML comment, waiting for its capture, must name a file the images
 * README lists, so the two lists cannot drift.
 */
class ReadmeScreenshotsTest {

    private val readme = repoFile("README.md").readText()
    private val imagesDir = "fastlane/metadata/android/en-US/images"
    private val image = Regex("""!\[[^\]]*\]\(([^)\s]+)\)""")
    private val comment = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)

    @Test
    fun `every image shown in README exists`() {
        val shown = image.findAll(comment.replace(readme, "")).map { it.groupValues[1] }
            .filterNot { it.startsWith("http") }
            .toList()
        for (path in shown) {
            assertTrue("README shows $path, which does not exist", File(repoRoot(), path).isFile)
        }
    }

    @Test
    fun `every image waiting in a comment is one the images README asks for`() {
        val listed = repoFile("$imagesDir/README.md").readText()
        val waiting = comment.findAll(readme).flatMap { image.findAll(it.value) }.map { it.groupValues[1] }.toList()
        assertTrue("found no commented-out screenshots; the parser has drifted", waiting.isNotEmpty() || !readme.contains("<!--"))
        for (path in waiting) {
            assertTrue("$path is not under $imagesDir", path.startsWith("$imagesDir/"))
            val name = path.removePrefix("$imagesDir/")
            assertTrue("$name is not listed in the images README", listed.contains("`$name`"))
        }
    }
}
