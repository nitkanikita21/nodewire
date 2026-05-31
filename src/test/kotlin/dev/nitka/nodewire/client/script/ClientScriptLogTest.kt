package dev.nitka.nodewire.client.script

import dev.nitka.nodewire.script.MessageKind
import dev.nitka.nodewire.script.ScriptMessage
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ClientScriptLogTest {

    @AfterEach fun cleanup() { ClientScriptLog.testSink = null }

    @Test fun `LOG messages forward, CHAT messages are dropped client-side`() {
        val seen = ArrayList<String>()
        ClientScriptLog.testSink = { seen += it }
        ClientScriptLog.drain(
            listOf(
                ScriptMessage("hello from client", MessageKind.LOG),
                ScriptMessage("should be ignored", MessageKind.CHAT),
            ),
        )
        assertEquals(listOf("hello from client"), seen)
    }

    @Test fun `empty drain does nothing`() {
        val seen = ArrayList<String>()
        ClientScriptLog.testSink = { seen += it }
        ClientScriptLog.drain(emptyList())
        assertEquals(emptyList<String>(), seen)
    }
}
