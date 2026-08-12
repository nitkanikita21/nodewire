package dev.nitka.nodewire.block.panel

import dev.nitka.nodewire.graph.PinType
import net.minecraft.nbt.CompoundTag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PanelElementTypeTest {
    @Test fun `catalog is the Dashpanels module set plus screens`() {
        val ids = PanelElements.ALL.map { it.id }.toSet()
        assertEquals(
            setOf(
                "switch", "momentary", "push_button", "key_switch", "emergency",
                "lever", "knob", "joystick", "bulb", "seven_segment", "buzzer", "label",
                "screen", "screen_small", "screen_wide", "screen_large", "screen_full",
            ),
            ids,
        )
    }

    @Test fun `footprints follow the Dashpanels modules`() {
        fun size(id: String) = PanelElements.byId(id)!!.let { it.cols to it.rows }
        assertEquals(2 to 3, size("switch"))
        assertEquals(3 to 3, size("momentary"))
        assertEquals(2 to 2, size("key_switch"))
        assertEquals(4 to 4, size("emergency"))
        assertEquals(3 to 5, size("lever"))
        assertEquals(2 to 2, size("knob"))
        assertEquals(4 to 4, size("joystick"))
        assertEquals(1 to 2, size("bulb"))
        assertEquals(6 to 4, size("seven_segment"))
        assertEquals(4 to 4, size("buzzer"))
        assertEquals(6 to 2, size("label"))
    }

    @Test fun `switch is a BOOL output with a remote set input`() {
        val t = PanelElements.byId("switch")!!
        assertEquals(listOf(PinType.BOOL), t.outputs.map { it.type })
        assertEquals("", t.outputs.single().name)
        assertEquals(listOf("set"), t.inputs.map { it.name })
        assertTrue(t.interactive)
    }

    @Test fun `joystick exposes vec2 plus trigger`() {
        val j = PanelElements.byId("joystick")!!
        assertEquals(listOf("" to PinType.VEC2, "trigger" to PinType.BOOL), j.outputs.map { it.name to it.type })
    }

    @Test fun `bulb and buzzer are pure inputs`() {
        assertFalse(PanelElements.byId("bulb")!!.interactive)
        assertEquals(listOf(PinType.BOOL), PanelElements.byId("buzzer")!!.inputs.map { it.type })
    }

    @Test fun `push_button grows a pin per configured button`() {
        val cfg = CompoundTag().apply { putInt("buttons", 3) }
        val e = PlacedElement("push_button", 0, 0, 6, 3, cfg, 0.0)
        val pins = PanelPins.pinsFor(e)
        assertEquals(listOf("", "b1", "b2", "b3"), pins.filter { it.dir == PanelPinDir.OUTPUT }.map { it.name })
        assertEquals(8 to 3, PanelElements.pushButtonFootprint(3, 1))
    }

    @Test fun `label has no pins`() {
        assertTrue(PanelElements.byId("label")!!.pins.isEmpty())
    }

    @Test fun `byId returns null for unknown`() { assertNull(PanelElements.byId("nope")) }
}
