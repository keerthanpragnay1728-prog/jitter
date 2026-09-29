package dev.molasses.core.diag

import dev.molasses.core.repoFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceComponentTest {

    private val pkg = "org.jitteros.app"
    private val cls = "dev.molasses.monitor.MolassesAccessibilityService"

    @Test
    fun `the full form matches`() {
        assertTrue(ServiceComponent.isEnabled("$pkg/$cls", pkg, cls))
    }

    @Test
    fun `the short form expands against the package in front of the slash`() {
        // The short form expands against the package before the slash, never
        // against the code namespace. When the two differ, a short form under
        // the application id names a class that is not ours.
        assertEquals(
            ServiceComponent.Name("dev.molasses", "dev.molasses.monitor.X"),
            ServiceComponent.unflatten("dev.molasses/.monitor.X"),
        )
        assertTrue(ServiceComponent.isEnabled("dev.molasses/.monitor.MolassesAccessibilityService", "dev.molasses", cls))
        assertFalse(
            "a short form under the application id names org.jitteros.app.monitor..., not our class",
            ServiceComponent.isEnabled("$pkg/.monitor.MolassesAccessibilityService", pkg, cls),
        )
    }

    @Test
    fun `ours among several entries matches, and others do not`() {
        val setting = "com.other.a11y/com.other.a11y.Service:$pkg/$cls:com.third/.S"
        assertTrue(ServiceComponent.isEnabled(setting, pkg, cls))
        assertFalse(ServiceComponent.isEnabled("com.other.a11y/com.other.a11y.Service:com.third/.S", pkg, cls))
    }

    @Test
    fun `a different package, a different class, or a case change does not match`() {
        assertFalse(ServiceComponent.isEnabled("dev.molasses/$cls", pkg, cls))
        assertFalse(ServiceComponent.isEnabled("$pkg/dev.molasses.monitor.Other", pkg, cls))
        assertFalse(ServiceComponent.isEnabled("$pkg/${cls.lowercase()}", pkg, cls))
    }

    @Test
    fun `malformed entries are not components`() {
        assertNull(ServiceComponent.unflatten("no-slash"))
        assertNull(ServiceComponent.unflatten("$pkg/"))
        assertFalse(ServiceComponent.isEnabled("", pkg, cls))
    }

    @Test
    fun `the repository checks through this, not a string compare`() {
        val repo = repoFile("app/src/main/java/dev/molasses/data/repo/SettingsRepository.kt").readText()
        assertTrue(
            repo.contains(
                "ServiceComponent.isEnabled(enabled, appContext.packageName, MolassesAccessibilityService::class.java.name)",
            ),
        )
        assertFalse(repo.contains("it.equals(expected, ignoreCase = true)"))
    }
}
