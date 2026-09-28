package dev.molasses.core.config

import dev.molasses.core.repoFile
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `accessibility_service_config.xml`, asserted as text.
 *
 * ## Why a test and not a code review
 * Every attribute checked here has already been changed by a commit that
 * meant to do something else. `dev.molasses` was dropped from `packageNames`
 * and `flagRetrieveInteractiveWindows` was re-added, both inside a commit
 * whose message described fixing an XML comment. Neither breaks a visible
 * feature, which is exactly why neither was noticed:
 *
 *  * Without `dev.molasses` in `packageNames`, no accessibility event arrives
 *    when the user leaves a target app. The session never closes, and time
 *    keeps accruing against an app that is no longer on screen. Nothing looks
 *    wrong; the ladder is just silently wrong.
 *  * `flagRetrieveInteractiveWindows` is one of the flags banking and UPI apps
 *    inspect. Re-adding it reintroduces the fraud warning this profile was
 *    narrowed to avoid.
 *
 * ## Why it reads the file rather than parsing resources
 * This runs in `tools/pure-verify`, which has no Android toolchain and no
 * `R` class. The file is read from disk as text, which is also the honest
 * level to assert at: the thing that ships is the file.
 *
 * Pure; no Android imports. The path walk means it works from the repository
 * root and from `tools/pure-verify` alike.
 */
class AccessibilityConfigTest {

    private val text: String by lazy { configFile().readText() }
    private val bytes: ByteArray by lazy { configFile().readBytes() }

    /** Attribute value, or null when the attribute is absent. */
    private fun attr(name: String): String? =
        Regex("""android:$name\s*=\s*"([^"]*)"""").find(text)?.groupValues?.get(1)

    @Test
    fun `our own package stays in packageNames`() {
        val packages = attr("packageNames")
            ?.split(",")
            ?.map { it.trim() }
            ?: error("packageNames attribute is missing entirely")

        assertTrue(
            "dev.molasses must stay in packageNames, or no event arrives when " +
                "the user leaves a target app and the session never closes. " +
                "Found: $packages",
            "dev.molasses" in packages,
        )
    }

    @Test
    fun `the monitored targets stay in packageNames`() {
        val packages = attr("packageNames")!!.split(",").map { it.trim() }
        for (target in listOf(
            "com.instagram.android",
            "com.twitter.android",
            "com.google.android.youtube",
        )) {
            assertTrue("$target missing from packageNames", target in packages)
        }
    }

    @Test
    fun `packageNames is scoped, never empty or wildcarded`() {
        // An absent or empty packageNames means "every app on the device",
        // which is the single thing that trips a banking app's inspection
        // hardest.
        val raw = attr("packageNames")
        assertTrue("packageNames must be present and non-empty", !raw.isNullOrBlank())
        val packages = raw!!.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        assertTrue("packageNames must not be empty", packages.isNotEmpty())
        assertFalse("packageNames must not contain a wildcard", packages.any { it == "*" })
    }

    @Test
    fun `flagRetrieveInteractiveWindows stays out of accessibilityFlags`() {
        val flags = attr("accessibilityFlags") ?: error("accessibilityFlags missing")
        assertFalse(
            "flagRetrieveInteractiveWindows is one of the flags banking apps " +
                "inspect. getWindows() returning empty is the accepted cost. " +
                "Found: $flags",
            flags.contains("flagRetrieveInteractiveWindows"),
        )
    }

    @Test
    fun `content reads stay off`() {
        assertEquals("false", attr("canRetrieveWindowContent"))
        assertEquals("false", attr("canPerformGestures"))
    }

    @Test
    fun `isAccessibilityTool stays false`() {
        // This is a friction tool, not an assistive one. Declaring otherwise
        // is a false declaration under Play's accessibility policy.
        assertEquals("false", attr("isAccessibilityTool"))
    }

    @Test
    fun `notificationTimeout stays zero`() {
        // Any non-zero value makes the platform coalesce same-type events,
        // which collapses a scroll burst into one callback.
        assertEquals("0", attr("notificationTimeout"))
    }

    @Test
    fun `the file is plain UTF-8 with no byte order mark`() {
        // A BOM before an XML declaration is an aapt2 failure mode on some
        // versions, and one was added to this exact file once.
        assertFalse(
            "accessibility_service_config.xml must not start with a UTF-8 BOM",
            bytes.size >= 3 &&
                bytes[0] == 0xEF.toByte() &&
                bytes[1] == 0xBB.toByte() &&
                bytes[2] == 0xBF.toByte(),
        )
        assertTrue(
            "file must be pure ASCII so no encoding can mangle it",
            bytes.all { it >= 0 },
        )
    }

    private fun configFile(): File =
        repoFile("app/src/main/res/xml/accessibility_service_config.xml")

    // ------------------------------------------------- the launcher handshake

    /**
     * `ForegroundEventRouter.LAUNCHER_CLASS_NAME` is matched against the class
     * name on an accessibility event. If it does not equal the activity the
     * manifest actually declares, `EventRoute.ExitToHome` is unreachable and
     * leaving a target app for home stops closing the session. Nothing throws;
     * the accounting just stops.
     *
     * The router holds the name as a string precisely so it can stay in the
     * pure module, which means nothing but this test ties the two together.
     */
    @Test
    fun `the router's launcher class name is the activity the manifest declares`() {
        val manifest = repoFile("app/src/main/AndroidManifest.xml").readText()
        val expected = dev.molasses.core.session.ForegroundEventRouter.LAUNCHER_CLASS_NAME

        val declared = Regex("""android:name="(\.[^"]*Launcher[^"]*)"""")
            .find(manifest)
            ?.groupValues
            ?.get(1)
            ?: error("no launcher activity declared in the manifest")

        // The manifest uses the leading-dot short form against the package.
        assertEquals("dev.molasses$declared", expected)
    }

    @Test
    fun `the launcher activity is declared as a home screen`() {
        val manifest = repoFile("app/src/main/AndroidManifest.xml").readText()
        assertTrue(
            "the launcher must declare category.HOME or it cannot be the " +
                "default launcher",
            manifest.contains("android.intent.category.HOME"),
        )
        assertTrue(
            "the launcher must declare category.DEFAULT alongside HOME",
            manifest.contains("android.intent.category.DEFAULT"),
        )
    }

    @Test
    fun `the manifest declares none of the permissions section 1 removed`() {
        val manifest = repoFile("app/src/main/AndroidManifest.xml").readText()
        for (permission in listOf(
            "android.permission.SYSTEM_ALERT_WINDOW",
            "android.permission.READ_PHONE_STATE",
            "android.permission.QUERY_ALL_PACKAGES",
            "android.permission.READ_CALL_LOG",
            "android.permission.ACCESS_NOTIFICATION_POLICY",
        )) {
            assertFalse(
                "$permission was removed for banking-app safety and must stay out",
                manifest.contains(permission),
            )
        }
    }

    @Test
    fun `the permission surface is exactly this list`() {
        // Every uses-permission the manifest declares, by name. A new one
        // fails here until it is added deliberately, with its reason in the
        // manifest. The two exact-alarm permissions are $ rem's, and
        // SCHEDULE_EXACT_ALARM is capped at API 32, where USE_EXACT_ALARM
        // takes over.
        val manifest = repoFile("app/src/main/AndroidManifest.xml").readText()
        val declared = Regex("""<uses-permission[^>]*android:name="([^"]+)"""")
            .findAll(manifest).map { it.groupValues[1] }.toSet()
        assertEquals(
            setOf(
                "android.permission.PACKAGE_USAGE_STATS",
                "android.permission.ACTIVITY_RECOGNITION",
                "android.permission.RECEIVE_BOOT_COMPLETED",
                "android.permission.POST_NOTIFICATIONS",
                "android.permission.HIGH_SAMPLING_RATE_SENSORS",
                "android.permission.VIBRATE",
                "android.permission.USE_EXACT_ALARM",
                "android.permission.SCHEDULE_EXACT_ALARM",
            ),
            declared,
        )
        val schedule = Regex("""<uses-permission[^>]*SCHEDULE_EXACT_ALARM[^>]*>""").find(manifest)!!.value
        assertTrue(schedule.contains("android:maxSdkVersion=\"32\""))
        val use = Regex("""<uses-permission[^>]*USE_EXACT_ALARM[^>]*>""").find(manifest)!!.value
        assertFalse("USE_EXACT_ALARM must not be capped", use.contains("maxSdkVersion"))
    }

    @Test
    fun `no foreground service is declared`() {
        // CLAUDE.md, "Service model". The AccessibilityService is system-bound
        // and needs no FGS; adding one would force a specialUse type.
        val manifest = repoFile("app/src/main/AndroidManifest.xml").readText()
        assertFalse(manifest.contains("android.permission.FOREGROUND_SERVICE"))
        assertFalse(manifest.contains("android:foregroundServiceType"))
    }

    @Test
    fun `the launcher declares its soft input mode rather than inheriting one`() {
        // Same reason as everything else in this file: a silent revert here
        // breaks nothing visibly. The default is adjustUnspecified, and
        // ViewRootImpl resolves that to adjustPan for a window with no
        // registered scroll containers, which a pure Compose hierarchy never
        // has. A pan translates the whole window far enough to carry the
        // header, the status line and Bit's row off the top of the screen,
        // and it would read as a layout bug rather than as a missing
        // attribute.
        val manifest = repoFile("app/src/main/AndroidManifest.xml").readText()
        val activity = Regex(
            """<activity[^>]*LauncherActivity[\s\S]*?>""",
        ).find(manifest)?.value ?: error("LauncherActivity is not declared")

        assertTrue(
            "LauncherActivity must declare adjustResize, or the unspecified " +
                "default resolves to adjustPan. Found: $activity",
            activity.contains("adjustResize"),
        )
        assertFalse(
            "adjustPan cannot be combined with adjustResize and would push " +
                "Bit's row off the top of the screen",
            activity.contains("adjustPan"),
        )
        assertTrue(
            "LauncherActivity is the HOME activity and singleTask, so it " +
                "must declare stateAlwaysHidden or returning from an app can " +
                "restore a keyboard the user never asked for",
            activity.contains("stateAlwaysHidden"),
        )
    }

    @Test
    fun `both activities declare a cutout mode, and it is not the overlays'`() {
        // Inherited window geometry is geometry nobody decided, and this is
        // the one of these that bites on a device shape rather than a device
        // setting, so it cannot be checked by looking at the phone on the
        // desk. The value is the status quo; the test is what stops it
        // becoming "always" by analogy with the overlays, which want the
        // opposite thing for a reason the manifest comment spells out.
        val manifest = repoFile("app/src/main/AndroidManifest.xml").readText()
        for (name in listOf("LauncherActivity", "SettingsActivity")) {
            val activity = Regex(
                """<activity[^>]*$name[\s\S]*?>""",
            ).find(manifest)?.value ?: error("$name is not declared")
            assertTrue(
                "$name must declare windowLayoutInDisplayCutoutMode. Found: $activity",
                activity.contains("windowLayoutInDisplayCutoutMode=\"default\""),
            )
        }
    }


    @Test
    fun `the event types are exactly the three the app acts on, and window content stays unreadable`() {
        // A content-change scroll proxy was built on these and reverted: it
        // could not tell scrolling from video playback and ate taps on
        // player controls. See CLAUDE.md before adding a fourth type.
        val xml = repoFile("app/src/main/res/xml/accessibility_service_config.xml").readText()
        assertTrue(xml.contains("android:accessibilityEventTypes=\"typeViewScrolled|typeWindowStateChanged|typeWindowsChanged\""))
        assertFalse(xml.contains("typeWindowContentChanged"))
        assertTrue(xml.contains("android:canRetrieveWindowContent=\"false\""))
        val service = repoFile("app/src/main/java/dev/molasses/monitor/MolassesAccessibilityService.kt").readText()
        val types = service.substring(service.indexOf("info.eventTypes =")).substringBefore("info.notificationTimeout")
        assertEquals(3, Regex("""AccessibilityEvent\.TYPE_""").findAll(types).count())
        for (t in listOf("TYPE_VIEW_SCROLLED", "TYPE_WINDOW_STATE_CHANGED", "TYPE_WINDOWS_CHANGED")) {
            assertTrue(t, types.contains("AccessibilityEvent.$t"))
        }
        assertFalse(service.contains("TYPE_WINDOW_CONTENT_CHANGED"))
    }
}
