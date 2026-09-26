package dev.molasses.core

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The root lookup the source-reading tests share. The half that matters is
 * the second test: with no marker above the start, the lookup throws, so a
 * test that cannot see the repository fails instead of skipping.
 */
class RepoRootTest {

    @Test
    fun `the root is found from the harness directory and from the root itself`() {
        val root = repoRoot()
        assertTrue(File(root, REPO_ROOT_MARKER).isFile)
        assertEquals(root, repoRoot(File(root, "tools/pure-verify")))
        assertEquals(root, repoRoot(root))
    }

    @Test
    fun `a directory with no marker above it throws rather than skips`() {
        val outside = Files.createTempDirectory("no-repo").toFile()
        try {
            repoRoot(outside)
            fail("repoRoot must throw when no directory above the start holds $REPO_ROOT_MARKER")
        } catch (e: AssertionError) {
            assertTrue(e.message!!.contains(REPO_ROOT_MARKER))
        } finally {
            outside.delete()
        }
    }
}
