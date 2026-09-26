package dev.molasses.core.lock

import dev.molasses.core.functionBody
import dev.molasses.core.repoFile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lock screen's copy, its order, and where its date comes from. Read as
 * text, because the screen and the overlay are compiled by nothing here.
 */
class LockScreenCopyTest {

    private val screen by lazy { repoFile("app/src/main/java/dev/molasses/ui/lock/LockScreen.kt").readText() }
    private val strings by lazy { repoFile("app/src/main/res/values/strings.xml").readText() }

    @Test
    fun `the lines are in the specified order`() {
        val body = functionBody(screen, "fun LockScreen(")
        val order = listOf(
            "BitStateMachine.BLINK_HALF",
            "R.string.lock_title",
            "R.string.lock_target_fmt",
            "R.string.lock_opens_by",
            "text = opensAtText",
            "R.string.lock_exit",
        ).map { body.indexOf(it) }
        assertTrue("a line is missing: $order", order.all { it >= 0 })
        assertTrue("the lines are out of order: $order", order == order.sorted())
    }

    @Test
    fun `the copy is the specified copy`() {
        assertTrue(strings.contains("""<string name="lock_title" translatable="false">BLOCKED</string>"""))
        assertTrue(strings.contains("""<string name="lock_target_fmt" translatable="false">TARGET // %1${'$'}s</string>"""))
        assertTrue(strings.contains("""<string name="lock_opens_by" translatable="false">WILL BE OPEN BY</string>"""))
        assertTrue(strings.contains("""<string name="lock_exit" translatable="false">[ ARCHITECT\'S SPACE ]</string>"""))
    }

    @Test
    fun `the time is formatted in the device locale with the system hour cycle`() {
        val fmt = functionBody(screen, "fun lockOpensAtText(")
        assertTrue(fmt.contains("DateFormat.is24HourFormat(context)"))
        assertTrue(fmt.contains("DateFormat.getBestDateTimePattern(locale"))
    }

    @Test
    fun `the service passes the opening instant from LockOpensAt`() {
        val service = repoFile("app/src/main/java/dev/molasses/monitor/MolassesAccessibilityService.kt").readText()
        val body = functionBody(service, "private fun enforceLockIfNeeded(")
        assertTrue(body.contains("opensAtWallMs = LockOpensAt.wallMs(locks, pkg, now)"))
    }

    @Test
    fun `the lock screen stays until the user leaves it`() {
        // No timed bounce: the only exit is the button.
        val overlay = repoFile("app/src/main/java/dev/molasses/overlay/LockOverlayManager.kt").readText()
        val flash = functionBody(overlay, "fun flash(")
        assertTrue(flash.contains("onExit = { exit(\"user\") }"))
        assertFalse("no timed exit in flash", Regex("""delay\(""").containsMatchIn(flash))
    }
}
