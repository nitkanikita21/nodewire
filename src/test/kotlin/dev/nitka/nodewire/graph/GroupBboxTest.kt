package dev.nitka.nodewire.graph

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GroupBboxTest {
    @Test fun emptyGroupReturnsZeroSizedAtAnchor() {
        val bbox = GroupBbox.compute(CanvasPos(10f, 20f), emptyList())
        assertEquals(10f, bbox.minX); assertEquals(20f, bbox.minY)
        assertEquals(10f, bbox.maxX); assertEquals(20f, bbox.maxY)
    }

    @Test fun unionsMemberRects() {
        val members = listOf(
            CanvasPos(0f, 0f) to (100 to 50),
            CanvasPos(200f, 100f) to (80 to 40),
        )
        val bbox = GroupBbox.compute(CanvasPos(0f, 0f), members)
        assertEquals(0f, bbox.minX); assertEquals(0f, bbox.minY)
        assertEquals(280f, bbox.maxX); assertEquals(140f, bbox.maxY)
    }
}
