package dev.nitka.nodewire.script

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The non-replicated client-read throw (spec §5.7 / D2). `setup` runs on BOTH
 * sides → the client knows every cell + its `replicated` flag, so reading a
 * `replicated = false` cell on the CLIENT is decidably an error and throws with
 * the spec's exact wording. Enabled in Phase 2c with the client-side flag.
 */
class NonReplicatedClientReadTest {
    private class M : ScriptModule() {
        var local by state(0, replicated = false)
        var synced by state(0, replicated = true)
    }

    @Test fun `reading a non-replicated cell on the client throws a clear error`() {
        val m = M()
        m.setClientSide(true)
        val ex = runCatching { @Suppress("UNUSED_EXPRESSION") m.local }.exceptionOrNull()
        assertTrue(ex?.message?.contains("not replicated") == true) { "got: ${ex?.message}" }
    }

    @Test fun `reading a replicated cell on the client is fine`() {
        val m = M()
        m.setClientSide(true)
        assertEquals(0, m.synced)
    }

    @Test fun `the server reads non-replicated cells without throwing`() {
        val m = M() // clientSide defaults false
        assertEquals(0, m.local)
    }

    @Test fun `reading a non-video input on the client throws a clear error`() {
        class M2 : ScriptModule() {
            val speed = input<Int>("speed")
            val cam = input<Video>("camera")
        }
        val m = M2().apply { setClientSide(true) }

        // Non-video input must throw
        val ex = runCatching { m.speed.value }.exceptionOrNull()
        assertTrue(ex is ScriptDeclException, "expected ScriptDeclException on client-side non-video input read")
        assertTrue(ex?.message?.contains("not available on the client") == true)

        // Video input must not throw (returns NONE by default on empty inputs)
        assertDoesNotThrow { m.cam.value }
    }
}
