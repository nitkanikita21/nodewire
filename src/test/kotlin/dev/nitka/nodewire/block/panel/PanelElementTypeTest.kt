package dev.nitka.nodewire.block.panel

import dev.nitka.nodewire.graph.PinType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PanelElementTypeTest {
    @Test fun `catalog has the expected element ids`() {
        val ids = PanelElements.ALL.map { it.id }.toSet()
        assertEquals(
            setOf(
                "toggle", "momentary", "selector", "slider", "knob",
                "lamp", "bar", "numeric",
                "screen", "screen_small", "screen_wide", "screen_large", "screen_full",
                "label",
            ),
            ids,
        )
    }

    @Test fun `toggle is a switch-footprint BOOL output with a remote set input`() {
        val t = PanelElements.byId("toggle")!!
        assertEquals(2, t.cols); assertEquals(3, t.rows) // Dashpanels switch module
        assertEquals(listOf(PinType.BOOL), t.outputs.map { it.type })
        assertEquals("", t.outputs.single().name) // primary keeps the bare id
        assertEquals(listOf("set"), t.inputs.map { it.name })
        assertEquals(PinType.BOOL, t.inputs.single().type)
        assertTrue(t.interactive)
    }

    @Test fun `lamp is a 1x2 BOOL input and not interactive`() {
        val l = PanelElements.byId("lamp")!!
        assertEquals(1, l.cols); assertEquals(2, l.rows) // Dashpanels bulb module
        assertEquals(listOf(PinType.BOOL), l.inputs.map { it.type })
        assertTrue(l.outputs.isEmpty())
        assertFalse(l.interactive)
    }

    @Test fun `every screen variant has video+enable in and a touch surface out`() {
        val sizes = mapOf(
            "screen" to (4 to 4),
            "screen_small" to (2 to 2),
            "screen_wide" to (8 to 4),
            "screen_large" to (8 to 8),
            "screen_full" to (16 to 16),
        )
        for ((id, wh) in sizes) {
            val s = PanelElements.byId(id)!!
            assertTrue(PanelElements.isScreen(id))
            assertEquals(wh.first, s.cols, id); assertEquals(wh.second, s.rows, id)
            assertEquals(listOf("" to PinType.VIDEO, "enable" to PinType.BOOL), s.inputs.map { it.name to it.type })
            assertEquals(listOf("touch", "touch_down"), s.outputs.map { it.name })
            assertEquals(listOf(PinType.VEC2, PinType.BOOL), s.outputs.map { it.type })
        }
        assertFalse(PanelElements.isScreen("toggle"))
    }

    @Test fun `label has no pins`() {
        assertTrue(PanelElements.byId("label")!!.pins.isEmpty())
    }

    @Test fun `byId returns null for unknown`() { assertNull(PanelElements.byId("nope")) }
}
