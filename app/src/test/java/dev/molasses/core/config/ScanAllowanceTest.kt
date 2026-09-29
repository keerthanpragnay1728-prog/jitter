package dev.molasses.core.config

import dev.molasses.core.repoFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The binary allowance in the encoding scanners covers one directory, by
 * path, and nothing else. A wider one would let a damaged source file hide
 * behind a binary-looking name.
 */
class ScanAllowanceTest {

    private val check by lazy { repoFile("tools/check-encoding.sh").readText() }
    private val history by lazy { repoFile("tools/scan-encoding-history.py").readText() }

    @Test
    fun `check-encoding allows exactly app-src-main-res-raw, by path`() {
        assertEquals(1, Regex("""RAW_BINARY_DIR="[^"]*"""").findAll(check).count())
        assertTrue(check.contains("RAW_BINARY_DIR=\"app/src/main/res/raw/\""))
        assertTrue(check.contains("case \"\$f\" in \"\$RAW_BINARY_DIR\"*) continue ;; esac"))
    }

    @Test
    fun `the history scan allows the same directory and no other`() {
        assertEquals(1, Regex("""RAW_BINARY_DIR = "[^"]*"""").findAll(history).count())
        assertTrue(history.contains("RAW_BINARY_DIR = \"app/src/main/res/raw/\""))
        assertTrue(history.contains("path.startswith(RAW_BINARY_DIR)"))
    }

    @Test
    fun `audio is not allowed by extension anywhere`() {
        for (ext in listOf("wav", "ogg", "mp3", "m4a")) {
            assertFalse(".$ext in check-encoding's extension list", check.contains("*.$ext"))
            assertFalse(".$ext in the history scan's skip list", history.contains("\".$ext\""))
        }
    }
}
