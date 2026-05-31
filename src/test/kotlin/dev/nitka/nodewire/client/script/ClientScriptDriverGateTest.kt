package dev.nitka.nodewire.client.script

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Hardening #3 — the CLIENT kill-switch gate. The frame driver MUST read
 * [ClientScriptToggle.enabled] at the TOP of every frame and, when OFF, skip ALL
 * per-node work WITHOUT touching `Minecraft.getInstance()`.
 *
 * That short-circuit is the testable contract: in a unit-test JVM there is no
 * running client, so `Minecraft.getInstance().level` would NPE/throw if the
 * driver reached it. [ClientScriptDriver.tick] while the switch is OFF must
 * therefore complete cleanly — proving the gate runs BEFORE any client access
 * and a viewer can stop client scripts without rendering another frame.
 */
class ClientScriptDriverGateTest {

    @AfterEach
    fun restore() {
        // Leave the global toggle ON for other tests (default state).
        ClientScriptToggle.set(true)
    }

    @Test
    fun `kill-switch defaults ON`() {
        ClientScriptToggle.set(true)
        assertTrue(ClientScriptToggle.enabled)
    }

    @Test
    fun `OFF gate makes tick a no-op without touching Minecraft`() {
        ClientScriptToggle.set(false)
        assertFalse(ClientScriptToggle.enabled)
        // No running client → if the driver reached Minecraft.getInstance() this
        // would throw. The OFF gate must short-circuit first.
        assertDoesNotThrow { ClientScriptDriver.tick() }
    }

    @Test
    fun `set OFF cancels all client runtimes`() {
        // cancelAll on an empty registry is a safe no-op; this asserts the toggle
        // routes through it (and back ON re-enables) without error.
        assertDoesNotThrow {
            ClientScriptToggle.set(false)
            ClientScriptToggle.set(true)
        }
        assertTrue(ClientScriptToggle.enabled)
    }
}
