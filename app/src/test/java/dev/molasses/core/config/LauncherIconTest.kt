package dev.molasses.core.config

import dev.molasses.core.repoFile
import dev.molasses.core.repoRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * The launcher icon: one adaptive icon, three layers, two colours that are
 * the app's own, and shapes a launcher's mask cannot clip. Read as text and
 * checked in numbers, because nothing here renders a drawable.
 */
class LauncherIconTest {

    private val res = "app/src/main/res"
    private val manifest by lazy { repoFile("app/src/main/AndroidManifest.xml").readText() }
    private val adaptive by lazy { repoFile("$res/mipmap-anydpi-v26/ic_launcher.xml").readText() }
    private val colors by lazy { repoFile("$res/values/colors.xml").readText() }
    private val colorKt by lazy { repoFile("app/src/main/java/dev/molasses/ui/theme/Color.kt").readText() }
    private val foreground by lazy { repoFile("$res/drawable/ic_launcher_terminal.xml").readText() }
    private val monochrome by lazy { repoFile("$res/drawable/ic_launcher_monochrome.xml").readText() }

    private fun colorRes(name: String): String =
        Regex("""<color name="$name">#([0-9A-Fa-f]{8})</color>""").find(colors)!!.groupValues[1].uppercase()

    private fun colorKt(name: String): String =
        Regex("""val $name = Color\(0x([0-9A-Fa-f]{8})\)""").find(colorKt)!!.groupValues[1].uppercase()

    @Test
    fun `the manifest's icon and round icon are both the adaptive icon`() {
        assertTrue(manifest.contains("android:icon=\"@mipmap/ic_launcher\""))
        assertTrue(manifest.contains("android:roundIcon=\"@mipmap/ic_launcher\""))
    }

    @Test
    fun `the adaptive icon declares background, foreground and monochrome, and each exists`() {
        assertTrue(adaptive.contains("<background android:drawable=\"@color/ic_launcher_background\"/>"))
        assertTrue(adaptive.contains("<foreground android:drawable=\"@drawable/ic_launcher_terminal\"/>"))
        assertTrue(adaptive.contains("<monochrome android:drawable=\"@drawable/ic_launcher_monochrome\"/>"))
        assertTrue(repoFile("$res/drawable/ic_launcher_terminal.xml").isFile)
        assertTrue(repoFile("$res/drawable/ic_launcher_monochrome.xml").isFile)
    }

    @Test
    fun `the icon's colours are PhosphorGreen and JitterBackground, and nothing else`() {
        assertEquals(colorKt("PhosphorGreen"), colorRes("phosphor_green"))
        assertEquals(colorKt("JitterBackground"), colorRes("ic_launcher_background"))
        for (layer in listOf(foreground, monochrome)) {
            val fills = Regex("""android:fillColor="([^"]+)"""").findAll(layer).map { it.groupValues[1] }.toSet()
            assertEquals(setOf("@color/phosphor_green"), fills)
            assertFalse("no literal colour in a layer", Regex("""#[0-9A-Fa-f]{6,8}""").containsMatchIn(layer))
        }
    }

    @Test
    fun `the monochrome layer has the foreground's shapes`() {
        fun paths(xml: String) = Regex("""android:pathData="([^"]+)"""").findAll(xml).map { it.groupValues[1] }.toList()
        assertEquals(paths(foreground), paths(monochrome))
    }

    /** Every point a command in [path] moves to or ends at. M, H, V and the end point of A; all absolute. */
    private fun points(path: String): List<Pair<Double, Double>> {
        val out = mutableListOf<Pair<Double, Double>>()
        var x = 0.0
        var y = 0.0
        for (m in Regex("""([MHVAZ])([^MHVAZ]*)""").findAll(path)) {
            val n = m.groupValues[2].trim().split(Regex("""[\s,]+""")).filter { it.isNotEmpty() }.map { it.toDouble() }
            when (m.groupValues[1]) {
                "M" -> { x = n[0]; y = n[1] }
                "H" -> x = n[0]
                "V" -> y = n[0]
                "A" -> { x = n[5]; y = n[6] }
                "Z" -> continue
            }
            out += x to y
        }
        return out
    }

    /** The rectangles in [path] written as `Mx0,y0 Hx1 Vy1 Hx0 Z`. */
    private fun rects(path: String): List<DoubleArray> =
        Regex("""M([\d.]+),([\d.]+) H([\d.]+) V([\d.]+) H([\d.]+) Z""").findAll(path).map {
            val v = it.groupValues.drop(1).map(String::toDouble)
            doubleArrayOf(v[0], v[1], v[2], v[3])
        }.toList()

    @Test
    fun `every shape is inside the 66dp safe zone, frame corners included`() {
        val all = Regex("""android:pathData="([^"]+)"""").findAll(foreground).flatMap { points(it.groupValues[1]) }.toList()
        assertTrue(all.isNotEmpty())
        for ((x, y) in all) assertTrue("($x,$y) outside 21..87", x in 21.0..87.0 && y in 21.0..87.0)
        // The frame's outer edge, and its rounded corner's farthest point,
        // against a circle of radius 33 about the centre.
        val frame = Regex("""android:name="frame"[\s\S]*?android:pathData="([^"]+)"""").find(foreground)!!.groupValues[1]
        val outer = points(frame.substringBefore(" Z"))
        val minX = outer.minOf { it.first }
        val minY = outer.minOf { it.second }
        val maxX = outer.maxOf { it.first }
        val maxY = outer.maxOf { it.second }
        val r = Regex("""A([\d.]+),""").find(frame)!!.groupValues[1].toDouble()
        val inset = r * (1 - 1 / sqrt(2.0))
        for ((cx, cy) in listOf(minX to minY, maxX to minY, minX to maxY, maxX to maxY)) {
            val px = if (cx < 54) cx + inset else cx - inset
            val py = if (cy < 54) cy + inset else cy - inset
            assertTrue("frame corner at ${hypot(px - 54, py - 54)} from centre", hypot(px - 54, py - 54) <= 33.0)
        }
        // Edges are straight, so the edge midpoints must be inside too.
        for (d in listOf(54 - minX, maxX - 54, 54 - minY, maxY - 54)) assertTrue(d <= 33.0)
    }

    @Test
    fun `nothing is thinner than 4 on the 108 canvas`() {
        val frame = Regex("""android:name="frame"[\s\S]*?android:pathData="([^"]+)"""").find(foreground)!!.groupValues[1]
        val outer = points(frame.substringBefore(" Z"))
        val inner = rects(frame).single()
        assertTrue(inner[0] - outer.minOf { it.first } >= 4)
        assertTrue(outer.maxOf { it.first } - inner[2] >= 4)
        assertTrue(inner[1] - outer.minOf { it.second } >= 4)
        assertTrue(outer.maxOf { it.second } - inner[3] >= 4)
        val prompt = Regex("""android:name="prompt"[\s\S]*?android:pathData="([^"]+)"""").find(foreground)!!.groupValues[1]
        val bars = rects(prompt)
        assertEquals("every subpath of the prompt is a plain rectangle", prompt.count { it == 'M' }, bars.size)
        for (b in bars) assertTrue("${b.toList()} is thinner than 4", minOf(b[2] - b[0], b[3] - b[1]) >= 4)
    }

    @Test
    fun `the water-drop drawable is gone, nothing names it, and no mipmap PNG is left`() {
        assertFalse(repoFile("$res/mipmap-anydpi-v26/ic_launcher.xml").parentFile.resolve("../drawable/ic_launcher_foreground.xml").exists())
        // Main sources only: this test names the old drawable to look for it.
        val src = File(repoRoot(), "app/src/main")
        val offenders = src.walkTopDown()
            .filter { it.isFile && (it.extension == "xml" || it.extension == "kt") }
            .filter { it.readText().contains("ic_launcher_foreground") }
            .map { it.name }.toList()
        assertEquals(emptyList<String>(), offenders)
        val pngs = File(repoRoot(), res).walkTopDown().filter { it.isFile && it.extension == "png" }.map { it.path }.toList()
        assertEquals(emptyList<String>(), pngs)
    }

    @Test
    fun `the store icon is a 512 square PNG, made by the committed script`() {
        val png = repoFile("fastlane/metadata/android/en-US/images/icon.png").readBytes()
        assertEquals(0x89.toByte(), png[0])
        assertEquals("PNG", String(png, 1, 3, Charsets.US_ASCII))
        fun int(at: Int) = ((png[at].toInt() and 0xFF) shl 24) or ((png[at + 1].toInt() and 0xFF) shl 16) or
            ((png[at + 2].toInt() and 0xFF) shl 8) or (png[at + 3].toInt() and 0xFF)
        assertEquals(512, int(16))
        assertEquals(512, int(20))
        val script = repoFile("tools/gen-icon.py").readText()
        assertTrue(script.contains("GREEN = (0x50, 0xFA, 0x7B)"))
        assertTrue(script.contains("BLACK = (0x00, 0x00, 0x00)"))
    }
}
