package dev.molasses.core.config

import dev.molasses.core.repoFile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reset policy is not a setting, and the engine is always handed
 * FIXED_WINDOW_6H.
 *
 * It used to be a setting that did nothing: the engine never applied a
 * change and its checkpoint wrote its startup policy back over the choice.
 * The enum and the proto field stay so stored files parse, which is exactly
 * why a stored ABSTINENCE_6H must not reach the engine on the next restart.
 */
class ResetPolicyRemovedTest {

    @Test
    fun `the stored policy is not read into the engine`() {
        val store = repoFile("app/src/main/java/dev/molasses/data/datastore/CycleStateStore.kt").readText()
        // An expression body, so sliced to the next top-level declaration
        // rather than by braces.
        val start = store.indexOf("fun CycleState.toEngineSnapshot()")
        assertTrue("toEngineSnapshot not found", start >= 0)
        val end = store.indexOf("\nfun ", start + 1).let { if (it < 0) store.length else it }
        val body = store.substring(start, end)
        assertFalse("the stored policy must not reach the engine", body.contains("resetPolicy"))
    }

    @Test
    fun `nothing writes a chosen policy any more`() {
        for (path in listOf(
            "app/src/main/java/dev/molasses/data/datastore/CycleStateStore.kt",
            "app/src/main/java/dev/molasses/data/repo/SettingsRepository.kt",
            "app/src/main/java/dev/molasses/ui/settings/SettingsViewModel.kt",
            "app/src/main/java/dev/molasses/ui/settings/SettingsScreen.kt",
            "app/src/main/java/dev/molasses/engine/FrictionEngine.kt",
        )) {
            assertFalse("$path still has setResetPolicy", repoFile(path).readText().contains("fun setResetPolicy("))
        }
    }

    @Test
    fun `the setting's copy is gone`() {
        val strings = repoFile("app/src/main/res/values/strings.xml").readText()
        for (name in listOf("settings_section_policy", "settings_policy_abstinence_title", "settings_policy_fixed_title")) {
            assertFalse("$name is still in strings.xml", strings.contains("name=\"$name\""))
        }
    }

    @Test
    fun `the proto keeps the field so existing files parse`() {
        val proto = repoFile("app/src/main/proto/cycle_state.proto").readText()
        assertTrue(proto.contains("CycleResetPolicyProto reset_policy = 10;"))
    }

    @Test
    fun `the engine and the reconciler have no second policy`() {
        for (path in listOf(
            "app/src/main/java/dev/molasses/engine/FrictionEngine.kt",
            "app/src/main/java/dev/molasses/monitor/ForegroundReconciler.kt",
        )) {
            val text = repoFile(path).readText()
            assertFalse("$path still branches on ABSTINENCE_6H", text.contains("CycleResetPolicy.ABSTINENCE_6H"))
            assertFalse("$path still reads a reset policy", text.contains("resetPolicy"))
        }
    }

    @Test
    fun `the store writes the one policy there is`() {
        val store = repoFile("app/src/main/java/dev/molasses/data/datastore/CycleStateStore.kt").readText()
        assertTrue(store.contains(".setResetPolicy(CycleResetPolicyProto.FIXED_WINDOW_6H)"))
    }
}
