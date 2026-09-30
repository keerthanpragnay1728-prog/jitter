package dev.molasses.core.safety

import dev.molasses.core.repoFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class OverlayKindTest {

    @Test
    fun `kinds come from the flags the overlays are shown with`() {
        assertEquals(OverlayKind.EXPIRED_GATE, OverlayKind.leaseGate(expired = true))
        assertEquals(OverlayKind.ENTRY_GATE, OverlayKind.leaseGate(expired = false))
        assertEquals(OverlayKind.LOCK_AT_ENTRY, OverlayKind.lock(atEntry = true))
        assertEquals(OverlayKind.LOCK_MID_SESSION, OverlayKind.lock(atEntry = false))
    }

    @Test
    fun `the per-kind home decision is gone, not kept answering yes`() {
        assertFalse(File(repoFile("app/src/main/java/dev/molasses/core/safety/OverlayKind.kt").parentFile, "HomeFirst.kt").exists())
        assertFalse(repoFile("app/src/main/java/dev/molasses/core/safety/OverlayKind.kt").readText().contains("fun sendsHome("))
    }
}
