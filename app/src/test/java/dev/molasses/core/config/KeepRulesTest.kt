package dev.molasses.core.config

import dev.molasses.core.repoFile
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The release keep rules cover what is reached by name.
 *
 * The release build is the only minified one and nothing here runs R8, so
 * this is a source check: every class the manifest names, every generated
 * proto message, and every persisted enum family has a rule. It cannot prove
 * R8 honours them; `assembleRelease` on a real toolchain is the only thing
 * that can.
 */
class KeepRulesTest {

    private val rules by lazy { repoFile("app/proguard-rules.pro").readText() }

    @Test
    fun `every class the manifest names is kept`() {
        val manifest = repoFile("app/src/main/AndroidManifest.xml").readText()
        val named = Regex("""android:name="\.([\w.]+)"""").findAll(manifest).map { "dev.molasses." + it.groupValues[1] }.toList()
        assertTrue("expected manifest components, found none", named.isNotEmpty())
        for (cls in named) {
            assertTrue("$cls is named in the manifest and has no -keep rule", rules.contains("-keep class $cls {"))
        }
    }

    @Test
    fun `the string-matched launcher class is kept`() {
        val router = repoFile("app/src/main/java/dev/molasses/core/session/ForegroundEventRouter.kt").readText()
        val name = Regex("""LAUNCHER_CLASS_NAME = "([\w.]+)"""").find(router)!!.groupValues[1]
        assertTrue("$name is matched by string and must keep its name", rules.contains("-keep class $name {"))
    }

    @Test
    fun `every generated proto message and enum is kept`() {
        val proto = repoFile("app/src/main/proto/cycle_state.proto").readText()
        val types = Regex("""^(message|enum) (\w+)""", RegexOption.MULTILINE).findAll(proto).toList()
        assertTrue(types.isNotEmpty())
        for (t in types) {
            val (kind, name) = t.destructured
            val rule = if (kind == "enum") "-keep enum dev.molasses.$name {" else "-keep class dev.molasses.$name {"
            assertTrue("$name from cycle_state.proto has no keep rule", rules.contains(rule))
        }
        assertTrue(rules.contains("-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }"))
    }

    @Test
    fun `app enums keep their constants and names`() {
        assertTrue(rules.contains("-keepclassmembers enum dev.molasses.** {"))
    }

    @Test
    fun `room finds its implementation by name`() {
        assertTrue(rules.contains("-keep class dev.molasses.data.db.MolassesDatabase_Impl"))
    }
}
