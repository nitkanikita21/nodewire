package dev.nitka.nodewire.block.panel

import net.minecraft.nbt.CompoundTag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ControlPanelStoreTest {
    private fun el(type: String, x: Int, y: Int, cols: Int, rows: Int) =
        PlacedElement(type, x, y, cols, rows, CompoundTag(), 0.0)

    @Test fun `add rejects overlapping footprint`() {
        val store = PanelElementStore()
        assertTrue(store.add(el("toggle", 0, 0, 2, 2)))
        assertFalse(store.add(el("lamp", 1, 1, 1, 1))) // overlaps the toggle
        assertTrue(store.add(el("lamp", 2, 2, 1, 1)))
        assertEquals(2, store.all().size)
    }

    @Test fun `add rejects out-of-bounds footprint`() {
        val store = PanelElementStore()
        assertFalse(store.add(el("numeric", 14, 0, 4, 2))) // 14+4 > 16
        assertTrue(store.all().isEmpty())
    }

    @Test fun `remove by any covered cell finds the element`() {
        val store = PanelElementStore()
        store.add(el("toggle", 0, 0, 2, 2))
        assertEquals("toggle@0,0", store.removeAt(PanelGrid.Cell(1, 1))?.pinId())
        assertTrue(store.all().isEmpty())
    }

    @Test fun `setValue replaces only when changed`() {
        val store = PanelElementStore()
        store.add(el("slider", 0, 0, 4, 1))
        assertTrue(store.setValue(PanelGrid.Cell(2, 0), 0.5))
        assertEquals(0.5, store.all().first().value, 1e-9)
        assertFalse(store.setValue(PanelGrid.Cell(2, 0), 0.5)) // no change
    }

    @Test fun `occupied reports every covered cell`() {
        val store = PanelElementStore()
        store.add(el("knob", 5, 5, 3, 3))
        assertEquals(9, store.occupied().size)
        assertTrue(PanelGrid.Cell(7, 7) in store.occupied())
        assertFalse(PanelGrid.Cell(8, 8) in store.occupied())
    }
}
