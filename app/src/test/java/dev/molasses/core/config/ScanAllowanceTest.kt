package dev.molasses.core.config

import dev.molasses.core.repoFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The binary allowance in the encoding scanners is by path, never by
 * extension: res/raw, the store icon, and PNGs in the store screenshot
 * directory. A wider one would let a damaged source file hide behind a
 * binary-looking name.
 */
class ScanAllowanceTest {

    private val check by lazy { repoFile("tools/check-encoding.sh").readText() }
    private val history by lazy { repoFile("tools/scan-encoding-history.py").readText() }

    private val raw = "app/src/main/res/raw/"
    private val icon = "fastlane/metadata/android/en-US/images/icon.png"
    private val shots = "fastlane/metadata/android/en-US/images/phoneScreenshots/"

    /** check-encoding's extension list, and the history scan's SKIP tuple: the by-extension allowances. */
    private val checkExtensions by lazy { check.substring(check.indexOf("is_binary() {")).substringBefore("\n}\n") }
    private val historySkip by lazy { history.substring(history.indexOf("SKIP = (")).substringBefore(")\n") }

    /** Each scanner's one path-based rule, read back from its own source. */
    private fun constant(source: String, name: String, sep: String): String =
        Regex("""$name$sep"([^"]*)"""").findAll(source).single().groupValues[1]

    /**
     * The allowance as both scripts write it, applied to [path]: a prefix for
     * res/raw, an exact match for the icon, and a prefix plus .png for the
     * screenshot directory. The two tests below pin that each script says
     * exactly this, so the model cannot drift from them.
     */
    private fun allowed(path: String, source: String, sep: String): Boolean =
        path.startsWith(constant(source, "RAW_BINARY_DIR", sep)) ||
            path == constant(source, "ICON_PNG", sep) ||
            (path.startsWith(constant(source, "SHOTS_PNG_DIR", sep)) && path.lowercase().endsWith(".png"))

    @Test
    fun `check-encoding allows res-raw, the icon and screenshot PNGs, by path`() {
        assertEquals(raw, constant(check, "RAW_BINARY_DIR", "="))
        assertEquals(icon, constant(check, "ICON_PNG", "="))
        assertEquals(shots, constant(check, "SHOTS_PNG_DIR", "="))
        val fn = check.substring(check.indexOf("binary_by_path() {")).substringBefore("\n}\n")
        assertTrue(fn.contains("\"\$RAW_BINARY_DIR\"*) return 0 ;;"))
        assertTrue(fn.contains("\"\$ICON_PNG\") return 0 ;;"))
        assertTrue(fn.contains("\"\$SHOTS_PNG_DIR\"*.png) return 0 ;;"))
        assertEquals("exactly three path rules", 3, Regex("""return 0 ;;""").findAll(fn).count())
        assertTrue(check.contains("binary_by_path \"\$f\" && continue"))
        assertTrue("fastlane is in scope, or the allowances name paths it never reads",
            check.contains("git ls-files -- app/src tools fastlane"))
    }

    @Test
    fun `the history scan allows the same three and no other`() {
        assertEquals(raw, constant(history, "RAW_BINARY_DIR", " = "))
        assertEquals(icon, constant(history, "ICON_PNG", " = "))
        assertEquals(shots, constant(history, "SHOTS_PNG_DIR", " = "))
        val fn = history.substring(history.indexOf("def binary_by_path(path):")).substringBefore("\n\n")
        assertTrue(fn.contains("path.startswith(RAW_BINARY_DIR)"))
        assertTrue(fn.contains("or path == ICON_PNG"))
        assertTrue(fn.contains("or (path.startswith(SHOTS_PNG_DIR) and path.lower().endswith(\".png\"))"))
        assertTrue(history.contains("if path.lower().endswith(SKIP) or binary_by_path(path):"))
    }

    @Test
    fun `a PNG in the screenshot directory passes, and a PNG anywhere else still fails`() {
        for ((source, sep) in listOf(check to "=", history to " = ")) {
            assertTrue(allowed("${shots}1.png", source, sep))
            assertTrue(allowed("${shots}8.PNG", source, sep))
            assertTrue(allowed(icon, source, sep))
            for (stray in listOf(
                "fastlane/metadata/android/en-US/images/stray.png",
                "fastlane/metadata/android/en-US/images/phoneScreenshots.png",
                "app/src/main/res/drawable/ic_launcher.png",
                "tools/shot.png",
                "${shots}notes.txt",
            )) {
                assertFalse("$stray must still be read as text", allowed(stray, source, sep))
            }
        }
    }

    @Test
    fun `PNG is not allowed by extension anywhere`() {
        assertFalse(checkExtensions.contains("png"))
        assertFalse(historySkip.contains("png"))
    }

    @Test
    fun `audio is not allowed by extension anywhere`() {
        for (ext in listOf("wav", "ogg", "mp3", "m4a")) {
            assertFalse(".$ext in check-encoding's extension list", checkExtensions.contains("*.$ext"))
            assertFalse(".$ext in the history scan's skip list", historySkip.contains("\".$ext\""))
        }
    }
}
