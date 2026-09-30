package dev.molasses.core.config

import dev.molasses.core.repoFile
import dev.molasses.core.repoRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.hypot

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
        // Fully opaque: the system tints the monochrome layer by its alpha, so
        // anything translucent would come out faint on a themed home screen.
        // Its fill is phosphor_green, alpha FF (pinned above), and nothing
        // lowers it.
        assertFalse(Regex("""[Aa]lpha""").containsMatchIn(monochrome))
    }

    /** A named path's data from the foreground layer. */
    private fun pathData(name: String): String =
        Regex("""android:name="$name"[\s\S]*?android:pathData="([^"]+)"""").find(foreground)!!.groupValues[1]

    /**
     * Every vertex of [path], which may use only absolute straight commands:
     * M, L, H, V and Z. A curve would put a point of the outline between
     * vertices, and the safe-zone check below would then be checking the
     * wrong points.
     */
    private fun vertices(path: String): List<Pair<Double, Double>> {
        val out = mutableListOf<Pair<Double, Double>>()
        var x = 0.0
        var y = 0.0
        for (m in Regex("""([A-Za-z])([^A-Za-z]*)""").findAll(path)) {
            val n = m.groupValues[2].trim().split(Regex("""[\s,]+""")).filter { it.isNotEmpty() }.map { it.toDouble() }
            when (m.groupValues[1]) {
                "M", "L" -> { x = n[0]; y = n[1] }
                "H" -> x = n[0]
                "V" -> y = n[0]
                "Z" -> continue
                else -> throw AssertionError("unexpected path command ${m.groupValues[1]} in $path")
            }
            out += x to y
        }
        return out
    }

    private val chevron by lazy { vertices(pathData("chevron")) }
    private val underscore by lazy { vertices(pathData("underscore")) }

    /** Perpendicular distance from [p] to the line through [a] and [b]. */
    private fun distance(p: Pair<Double, Double>, a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val dx = b.first - a.first
        val dy = b.second - a.second
        return kotlin.math.abs((p.first - a.first) * dy - (p.second - a.second) * dx) / hypot(dx, dy)
    }

    @Test
    fun `the icon is a chevron and an underscore, with no frame`() {
        val names = Regex("""android:name="([^"]+)"""").findAll(foreground).map { it.groupValues[1] }.toList()
        assertEquals(listOf("chevron", "underscore"), names)
        assertFalse(foreground.contains("frame"))
        assertFalse("no curves, so every corner is sharp", Regex("""pathData="[^"]*[AaCcQqSsTt]""").containsMatchIn(foreground))
        assertFalse("no even-odd hole, which is how the frame was cut", foreground.contains("fillType"))
        // A mitred chevron: six points, and the tip is a single sharp vertex
        // on each side rather than a cut or a bevel.
        assertEquals(6, chevron.size)
        assertEquals(4, underscore.size)
    }

    @Test
    fun `every point is inside the 66dp safe circle`() {
        // Straight edges only, so the farthest point of each shape from the
        // centre is one of its vertices.
        for ((x, y) in chevron + underscore) {
            assertTrue("($x,$y) is ${hypot(x - 54, y - 54)} from the centre", hypot(x - 54, y - 54) <= 33.0)
        }
    }

    @Test
    fun `the pair is 40 to 44 wide, centred as a group, on one baseline`() {
        val all = chevron + underscore
        val minX = all.minOf { it.first }
        val maxX = all.maxOf { it.first }
        val minY = all.minOf { it.second }
        val maxY = all.maxOf { it.second }
        assertTrue("group width ${maxX - minX}", maxX - minX in 40.0..44.0)
        assertEquals(54.0, (minX + maxX) / 2, 0.5)
        assertEquals(54.0, (minY + maxY) / 2, 0.5)
        assertEquals("the underscore sits on the chevron's baseline", chevron.maxOf { it.second }, underscore.maxOf { it.second }, 0.0)
        assertTrue("the underscore is to the right of the chevron", underscore.minOf { it.first } > chevron.maxOf { it.first })
    }

    @Test
    fun `nothing is thinner than 6 on the 108 canvas`() {
        val w = underscore.maxOf { it.first } - underscore.minOf { it.first }
        val h = underscore.maxOf { it.second } - underscore.minOf { it.second }
        assertTrue("underscore is $w by $h", minOf(w, h) >= 6.0)
        // Each arm's thickness: from its inner end corner to its outer edge.
        // Vertices in order: top end inner, top end outer, tip outer,
        // bottom end outer, bottom end inner, tip inner.
        assertTrue(distance(chevron[0], chevron[1], chevron[2]) >= 6.0)
        assertTrue(distance(chevron[4], chevron[2], chevron[3]) >= 6.0)
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
