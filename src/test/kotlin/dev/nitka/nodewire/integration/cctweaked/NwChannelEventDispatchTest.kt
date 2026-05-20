package dev.nitka.nodewire.integration.cctweaked

import dan200.computercraft.api.peripheral.IComputerAccess
import dev.nitka.nodewire.graph.PinValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NwChannelEventDispatchTest {

    private class FakeComputer : IComputerAccess {
        val events = mutableListOf<Triple<String, Any?, Any?>>()

        override fun queueEvent(event: String, vararg args: Any?) {
            events += Triple(event, args.getOrNull(0), args.getOrNull(1))
        }

        // Unused methods — return defaults / throw if unexpectedly called.
        override fun getAttachmentName(): String = "test"
        override fun getAvailablePeripherals(): MutableMap<String, dan200.computercraft.api.peripheral.IPeripheral> = mutableMapOf()
        override fun getAvailablePeripheral(name: String): dan200.computercraft.api.peripheral.IPeripheral? = null
        override fun mount(desiredLoc: String, contents: dan200.computercraft.api.filesystem.Mount, driveName: String?): String? = null
        override fun mountWritable(desiredLoc: String, contents: dan200.computercraft.api.filesystem.WritableMount, driveName: String?): String? = null
        override fun unmount(location: String?) {}
        override fun getID(): Int = 0
        override fun getMainThreadMonitor(): dan200.computercraft.api.peripheral.WorkMonitor =
            throw UnsupportedOperationException()
    }

    @Test fun `identical snapshots emit nothing`() {
        val c = FakeComputer()
        val snap = mapOf("speed" to PinValue.Float(1f))
        NwChannelEventDispatch.diffAndBroadcast(listOf(c to false), prev = snap, new = snap)
        assertTrue(c.events.isEmpty(), "expected no events, got ${c.events}")
    }

    @Test fun `changed value emits one event`() {
        val c = FakeComputer()
        NwChannelEventDispatch.diffAndBroadcast(
            listOf(c to false),
            prev = mapOf("speed" to PinValue.Float(1f)),
            new = mapOf("speed" to PinValue.Float(2f)),
        )
        assertEquals(1, c.events.size)
        val (ev, name, value) = c.events[0]
        assertEquals("nodewire_channel", ev)
        assertEquals("speed", name)
        assertEquals(2.0, (value as Number).toDouble())
    }

    @Test fun `removed channel emits nil value`() {
        val c = FakeComputer()
        NwChannelEventDispatch.diffAndBroadcast(
            listOf(c to false),
            prev = mapOf("gone" to PinValue.Bool(true)),
            new = emptyMap(),
        )
        assertEquals(1, c.events.size)
        assertEquals("gone", c.events[0].second)
        assertEquals(null, c.events[0].third)
    }

    @Test fun `needsInitialSync emits all new channels`() {
        val c = FakeComputer()
        NwChannelEventDispatch.diffAndBroadcast(
            listOf(c to true),
            prev = mapOf("a" to PinValue.Bool(true)), // ignored when needsInitialSync
            new = mapOf("a" to PinValue.Bool(true), "b" to PinValue.Int(7)),
        )
        assertEquals(2, c.events.size)
        val names = c.events.map { it.second }.toSet()
        assertEquals(setOf("a", "b"), names)
    }
}
