package dev.nitka.nodewire.block

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ScreenSpanTest {

    @Test
    fun `right axis matches the renderer's corner winding`() {
        assertEquals(Direction.WEST, ScreenSpan.rightOf(Direction.NORTH))
        assertEquals(Direction.EAST, ScreenSpan.rightOf(Direction.SOUTH))
        assertEquals(Direction.SOUTH, ScreenSpan.rightOf(Direction.WEST))
        assertEquals(Direction.NORTH, ScreenSpan.rightOf(Direction.EAST))
    }

    @Test
    fun `corners normalize to bottom-left anchor in any pick order`() {
        // SOUTH-facing wall: right = EAST (+X), up = +Y.
        val a = BlockPos(2, 64, 7)
        val b = BlockPos(4, 66, 7)
        val expect = ScreenSpan.Rect(BlockPos(2, 64, 7), 3, 3)
        assertEquals(expect, ScreenSpan.rect(Direction.SOUTH, a, b))
        assertEquals(expect, ScreenSpan.rect(Direction.SOUTH, b, a))
        // Opposite diagonal (top-left ↔ bottom-right) — same rect.
        assertEquals(expect, ScreenSpan.rect(Direction.SOUTH, BlockPos(2, 66, 7), BlockPos(4, 64, 7)))
    }

    @Test
    fun `north facing flips the horizontal axis`() {
        // NORTH-facing: right = WEST (−X) → the anchor is the MAX-x corner.
        val r = ScreenSpan.rect(Direction.NORTH, BlockPos(0, 10, 3), BlockPos(-2, 12, 3))
        assertEquals(ScreenSpan.Rect(BlockPos(0, 10, 3), 3, 3), r)
    }

    @Test
    fun `non-coplanar or oversized rejects`() {
        assertNull(ScreenSpan.rect(Direction.SOUTH, BlockPos(0, 0, 0), BlockPos(1, 1, 1)))
        assertNull(
            ScreenSpan.rect(
                Direction.SOUTH,
                BlockPos(0, 0, 0),
                BlockPos(ScreenSpan.MAX, 0, 0), // MAX+1 columns
            ),
        )
    }

    @Test
    fun `touch px maps corners and centre into canvas space (y-down)`() {
        // SOUTH-facing 2×2 panel anchored at (0,0,0); surface = 512×512.
        val anchor = BlockPos(0, 0, 0)
        // Bottom-left block, bottom-left corner of the face (u≈0, v≈0) →
        // canvas px (0, bottom) = (0, 511).
        var px = ScreenSpan.touchPx(Direction.SOUTH, anchor, 2, 2, anchor, 0.0, 0.0, 1.0)!!
        assertEquals(0, px[0]); assertEquals(511, px[1])
        // Top-right block (1,1,0), top-right corner (u≈1, v≈1) → (511, 0).
        px = ScreenSpan.touchPx(Direction.SOUTH, anchor, 2, 2, BlockPos(1, 1, 0), 2.0, 2.0, 1.0)!!
        assertEquals(511, px[0]); assertEquals(0, px[1])
        // Centre of the panel (hit at the bottom-left cell's far corner) → (256, 256).
        px = ScreenSpan.touchPx(Direction.SOUTH, anchor, 2, 2, BlockPos(0, 0, 0), 1.0, 1.0, 1.0)!!
        assertEquals(256, px[0])
        assertEquals(256, px[1])
    }

    @Test
    fun `touch px north facing flips horizontal`() {
        // NORTH-facing 1×1 at origin: u = 1 - lx → a hit near x=0 lands at the RIGHT of the canvas.
        val px = ScreenSpan.touchPx(Direction.NORTH, BlockPos.ZERO, 1, 1, BlockPos.ZERO, 0.05, 0.5, 0.0)!!
        assertEquals(243, px[0]) // (1-0.05)*256 = 243.2 → 243
        assertEquals(128, px[1]) // (1-0.5)*256 = 128
    }

    @Test
    fun `touch outside the grid rejects`() {
        assertNull(ScreenSpan.touchPx(Direction.SOUTH, BlockPos.ZERO, 2, 2, BlockPos(5, 0, 0), 5.5, 0.5, 1.0))
    }

    @Test
    fun `cells enumerate the full grid from the anchor`() {
        val rect = ScreenSpan.Rect(BlockPos(5, 60, 9), 2, 2)
        val cells = ScreenSpan.cells(Direction.SOUTH, rect).toSet()
        assertEquals(
            setOf(
                BlockPos(5, 60, 9), BlockPos(6, 60, 9),
                BlockPos(5, 61, 9), BlockPos(6, 61, 9),
            ),
            cells,
        )
        assertEquals(4, cells.size)
    }
}
