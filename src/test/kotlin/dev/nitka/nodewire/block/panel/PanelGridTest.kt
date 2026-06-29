package dev.nitka.nodewire.block.panel

import net.minecraft.core.Direction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PanelGridTest {
    @Test fun `fits respects 16x16 bounds`() {
        assertTrue(PanelGrid.fits(PanelGrid.Cell(0, 0), 2, 2))
        assertTrue(PanelGrid.fits(PanelGrid.Cell(14, 15), 2, 1))
        assertFalse(PanelGrid.fits(PanelGrid.Cell(15, 15), 2, 2)) // 16,16 out
        assertFalse(PanelGrid.fits(PanelGrid.Cell(-1, 0), 1, 1))
    }

    @Test fun `overlap detects shared cells`() {
        val occ = PanelGrid.footprintCells(PanelGrid.Cell(2, 2), 2, 2).toSet()
        assertTrue(PanelGrid.overlaps(occ, PanelGrid.Cell(3, 3), 2, 2))
        assertFalse(PanelGrid.overlaps(occ, PanelGrid.Cell(4, 4), 2, 2))
    }

    @Test fun `footprint enumerates the rectangle`() {
        assertEquals(
            setOf(PanelGrid.Cell(5,6), PanelGrid.Cell(6,6), PanelGrid.Cell(5,7), PanelGrid.Cell(6,7)),
            PanelGrid.footprintCells(PanelGrid.Cell(5, 6), 2, 2).toSet(),
        )
    }

    @Test fun `south face spin0 maps top-left and bottom-right corners`() {
        // SOUTH face, hit at the block-relative top-left of the face → cell (0,0).
        val tl = PanelGrid.hitToGrid(Direction.SOUTH, 0, /*x*/0.0, /*y*/1.0, /*z*/1.0)
        assertNotNull(tl)
        assertEquals(PanelGrid.Cell(0, 0), tl!!.cell)
        // Hit near the bottom-right of the face → cell (15,15).
        val br = PanelGrid.hitToGrid(Direction.SOUTH, 0, 0.999, 0.001, 1.0)
        assertEquals(PanelGrid.Cell(15, 15), br!!.cell)
    }

    @Test fun `sub-cell fraction is within unit interval`() {
        val h = PanelGrid.hitToGrid(Direction.SOUTH, 0, 0.5, 0.5, 1.0)!!
        assertTrue(h.uFrac in 0.0..1.0)
        assertTrue(h.vFrac in 0.0..1.0)
    }

    @Test fun `spin rotates the grid 90 degrees`() {
        val a = PanelGrid.hitToGrid(Direction.SOUTH, 0, 0.0, 1.0, 1.0)!!.cell // top-left at spin0
        val b = PanelGrid.hitToGrid(Direction.SOUTH, 1, 0.0, 1.0, 1.0)!!.cell // same hit, spin1
        assertTrue(a != b) // rotation actually changes the addressed cell
    }
}
