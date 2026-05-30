package dev.nitka.nodewire.script

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Facade-level test for the script debug-message buffer (log/chat -> drain). */
class MessagesTest {
    private class Mod : ScriptModule()

    @Test
    fun `log and chat buffer in order, drain returns then clears`() {
        val m = Mod()
        m.log("a")
        m.chat("b")
        m.log("c")

        assertEquals(
            listOf(
                ScriptMessage("a", MessageKind.LOG),
                ScriptMessage("b", MessageKind.CHAT),
                ScriptMessage("c", MessageKind.LOG),
            ),
            m.drainMessages(),
        )
        assertTrue(m.drainMessages().isEmpty(), "buffer should be empty after a drain")
    }

    @Test
    fun `messages are capped per tick`() {
        val m = Mod()
        repeat(100) { m.log("x") }
        assertEquals(64, m.drainMessages().size)
    }
}
