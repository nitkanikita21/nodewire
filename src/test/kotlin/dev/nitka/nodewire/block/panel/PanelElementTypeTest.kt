package dev.nitka.nodewire.block.panel

import dev.nitka.nodewire.graph.PinType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PanelElementTypeTest {
    @Test fun `catalog has the ten v1 element ids`() {
        val ids = PanelElements.ALL.map { it.id }.toSet()
        assertEquals(
            setOf("toggle","momentary","selector","slider","knob","lamp","bar","numeric","screen","label"),
            ids,
        )
    }

    @Test fun `toggle is a 2x2 BOOL output`() {
        val t = PanelElements.byId("toggle")!!
        assertEquals(2, t.cols); assertEquals(2, t.rows)
        assertEquals(PanelPinDir.OUTPUT, t.pinDir)
        assertEquals(PinType.BOOL, t.pinType)
    }

    @Test fun `lamp is a 1x1 BOOL input`() {
        val l = PanelElements.byId("lamp")!!
        assertEquals(1, l.cols); assertEquals(1, l.rows)
        assertEquals(PanelPinDir.INPUT, l.pinDir)
        assertEquals(PinType.BOOL, l.pinType)
    }

    @Test fun `label has no pin and screen is the only resizable type`() {
        assertEquals(PanelPinDir.NONE, PanelElements.byId("label")!!.pinDir)
        assertNull(PanelElements.byId("label")!!.pinType)
        assertTrue(PanelElements.byId("screen")!!.resizable)
        assertTrue(PanelElements.ALL.filter { it.resizable }.map { it.id } == listOf("screen"))
    }

    @Test fun `byId returns null for unknown`() { assertNull(PanelElements.byId("nope")) }
}
