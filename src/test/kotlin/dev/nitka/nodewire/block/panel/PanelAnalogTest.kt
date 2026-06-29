package dev.nitka.nodewire.block.panel

import net.minecraft.nbt.CompoundTag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PanelAnalogTest {
    @Test fun `slider continuous maps 0_1 onto min_max`() {
        assertEquals(0.0, PanelGrid.sliderValue(0.0, 0.0, 10.0, 0.0), 1e-9)
        assertEquals(10.0, PanelGrid.sliderValue(1.0, 0.0, 10.0, 0.0), 1e-9)
        assertEquals(5.0, PanelGrid.sliderValue(0.5, 0.0, 10.0, 0.0), 1e-9)
    }

    @Test fun `slider step snaps`() {
        assertEquals(2.0, PanelGrid.sliderValue(0.23, 0.0, 10.0, 2.0), 1e-9) // 2.3 → snap to 2
    }

    @Test fun `selector wraps forward and back`() {
        assertEquals(1, PanelGrid.selectorNext(0, 3, false))
        assertEquals(0, PanelGrid.selectorNext(2, 3, false))
        assertEquals(2, PanelGrid.selectorNext(0, 3, true))
    }

    @Test fun `outputs and inputs derive from element pin direction`() {
        val els = listOf(
            PlacedElement("toggle", 0, 0, 2, 2, CompoundTag(), 0.0),
            PlacedElement("lamp", 4, 0, 1, 1, CompoundTag(), 0.0),
        )
        assertEquals(listOf("toggle@0,0"), PanelPins.outputs(els).map { it.id })
        assertEquals(listOf("lamp@4,0"), PanelPins.inputs(els).map { it.id })
    }
}
