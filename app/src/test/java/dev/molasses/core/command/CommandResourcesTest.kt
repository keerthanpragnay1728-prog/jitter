package dev.molasses.core.command

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The command bar's resources, asserted as text.
 *
 * ## Why this is a test
 * `CommandRegistry` holds resource ids handed to it by the Android layer, and
 * it cannot dereference them: a row pointing at the wrong string, or at a
 * string that does not exist, is invisible to every other test here. The ids
 * themselves are checked by the compiler; what is not checked anywhere else is
 * that `strings.xml` contains a usage and a description for every registered
 * verb, and that the usage shown in the manual is the shape the parser
 * actually accepts.
 *
 * ## Why the manifest is in scope
 * The INTENT surface reports a command unavailable when nothing on the device
 * resolves its Intent. On API 30+ `resolveActivity` answers only for actions
 * declared in the manifest's `queries` block, so an action missing from that
 * list makes `$ alarm 6am` report "no clock app" on a phone that has three.
 * That failure is silent, device-only, and looks exactly like a correct
 * answer, which is why it is pinned here.
 *
 * Pure; no Android imports. Reads the shipped files, which is the honest
 * level to assert at.
 */
class CommandResourcesTest {

    private val strings: String by lazy {
        repoFile("app/src/main/res/values/strings.xml").readText()
    }

    private val manifest: String by lazy {
        repoFile("app/src/main/AndroidManifest.xml").readText()
    }

    /** The verbs the registry knows, read off a registry built with dummy ids. */
    private val verbs: List<String> =
        CommandRegistry(
            CommandRegistry.Keys(
                1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14,
                15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28,
            ),
        ).specs.map { it.verb }

    // ------------------------------------------------------------- strings

    @Test
    fun `every registered verb has a usage and a description string`() {
        for (verb in verbs) {
            assertNotNull("missing cmd_usage_$verb", stringValue("cmd_usage_$verb"))
            assertNotNull("missing cmd_desc_$verb", stringValue("cmd_desc_$verb"))
        }
    }

    @Test
    fun `the usage resource is the shape the parser accepts`() {
        // Two sources of truth would drift, and the one the user sees is the
        // resource while the one that decides is the parser.
        for (verb in verbs) {
            assertEquals(
                "cmd_usage_$verb does not match CommandParser.USAGE",
                CommandParser.USAGE.getValue(verb),
                stringValue("cmd_usage_$verb"),
            )
        }
    }

    @Test
    fun `usage strings are not translatable`() {
        // They are the grammar itself. A translated verb does not parse.
        for (verb in verbs) {
            assertTrue(
                "cmd_usage_$verb should be translatable=false",
                Regex("""<string name="cmd_usage_$verb" translatable="false">""")
                    .containsMatchIn(strings),
            )
        }
    }

    @Test
    fun `usage strings still carry no concrete values`() {
        // The manual is generated from these, and a completion that could
        // offer a filled in duration could arm a real lock.
        for (verb in verbs) {
            val usage = stringValue("cmd_usage_$verb")!!
            assertTrue("cmd_usage_$verb has a digit: $usage", usage.none { it.isDigit() })
        }
    }

    /**
     * Named individually rather than matched by prefix, so deleting one is a
     * failure here rather than a vacuous pass.
     *
     * cmd_na_wifi_toggle and cmd_na_dnd_toggle used to be on this list. They
     * are gone because the refusals are gone: both modifiers now open the
     * panel and say what could not be done, so the copy moved to
     * cmd_ack_wifi_panel_no_toggle and cmd_ack_dnd_panel_no_toggle.
     */
    private val unavailableReasons = listOf(
        "cmd_na_no_allowance",
        "cmd_na_relief_while_locked",
        "cmd_na_no_log",
        "cmd_na_no_scheduling",
        "cmd_na_privileged",
        "cmd_na_no_panel",
        "cmd_na_no_clock_app",
        "cmd_na_relief_needs_monitor",
        "cmd_na_wiring",
    )

    @Test
    fun `every unavailable reason exists`() {
        for (name in unavailableReasons) assertNotNull("missing $name", stringValue(name))
    }

    @Test
    fun `every acknowledgement exists and takes no arguments`() {
        // The two panel acknowledgements that answer a modifier are the
        // reason this test exists: they replaced refusals, so the coverage
        // that was on the reason list has to land somewhere or the copy could
        // be deleted with nothing failing.
        for (name in listOf(
            "cmd_ack_wifi_panel",
            "cmd_ack_dnd_panel",
            "cmd_ack_wifi_panel_no_toggle",
            "cmd_ack_dnd_panel_no_toggle",
        )) {
            val value = stringValue(name)
            assertNotNull("missing $name", value)
            assertTrue("$name must take no arguments", !value!!.contains("%"))
        }
    }

    @Test
    fun `an unavailable reason takes no format arguments`() {
        // A surface knows which command it is answering about, not what the
        // user typed, so the dispatcher has nothing to interpolate. A reason
        // with a specifier would render with an empty string in it.
        val reasons = Regex("""<string name="(cmd_na_[a-z_]+)"[^>]*>(.*?)</string>""")
            .findAll(strings)
        var seen = 0
        for (m in reasons) {
            seen += 1
            assertTrue(
                "${m.groupValues[1]} must take no arguments",
                !m.groupValues[2].contains("%"),
            )
        }
        // Tied to the list above rather than a number written here, so
        // removing a reason cannot leave this guard silently over-specified.
        assertTrue(
            "found $seen cmd_na_ strings, expected at least " +
                "${unavailableReasons.size}; the regex has rotted",
            seen >= unavailableReasons.size,
        )
    }

    // ------------------------------------------------------------ manifest

    @Test
    fun `every probed action is visible to PackageManager`() {
        // These are the actions the INTENT surface resolves against. The list
        // is spelled out rather than imported because the surface lives in the
        // Android layer, which this module cannot see.
        val probed = listOf(
            "android.intent.action.SET_ALARM",
            "android.intent.action.SET_TIMER",
            "android.settings.panel.action.WIFI",
            "android.settings.WIFI_SETTINGS",
            "android.settings.NOTIFICATION_POLICY_ACCESS_SETTINGS",
        )
        val queries = Regex("""<queries>(.*?)</queries>""", RegexOption.DOT_MATCHES_ALL)
            .find(manifest)
            ?.groupValues
            ?.get(1)
            ?: error("no queries block in the manifest")

        for (action in probed) {
            assertTrue(
                "$action is probed but not declared in <queries>",
                queries.contains("""android:name="$action""""),
            )
        }
    }

    // -------------------------------------------------------------- helpers

    /** The text of one string resource, XML entities resolved, or null. */
    private fun stringValue(name: String): String? =
        Regex("""<string name="$name"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .find(strings)
            ?.groupValues
            ?.get(1)
            ?.replace("&lt;", "<")
            ?.replace("&gt;", ">")
            ?.replace("&amp;", "&")

    private fun repoFile(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir")!!).absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("could not find $relative above ${System.getProperty("user.dir")}")
    }
}
