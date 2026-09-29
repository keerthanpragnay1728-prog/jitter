package dev.molasses.core.config

import dev.molasses.core.repoFile
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Room is told where to write its schema. The JSON itself is produced by the
 * Room annotation processor at compile time, which nothing here runs, so its
 * presence cannot be asserted from this environment; only that the build will
 * write it.
 */
class RoomSchemaTest {

    @Test
    fun `the schema is exported to app slash schemas`() {
        val build = repoFile("app/build.gradle.kts").readText()
        assertTrue(build.contains("arg(\"room.schemaLocation\", \"\$projectDir/schemas\")"))
        val db = repoFile("app/src/main/java/dev/molasses/data/db/MolassesDatabase.kt").readText()
        assertTrue(db.contains("exportSchema = true"))
    }
}
