package dev.nitka.nodewire.ui.layout

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PaddingValuesTest {
    @Test fun uniform() {
        val p = PaddingValues(8)
        assertEquals(8, p.start); assertEquals(8, p.top); assertEquals(8, p.end); assertEquals(8, p.bottom)
        assertEquals(16, p.horizontal); assertEquals(16, p.vertical)
    }

    @Test fun symmetric() {
        val p = PaddingValues(horizontal = 12, vertical = 4)
        assertEquals(12, p.start); assertEquals(4, p.top); assertEquals(12, p.end); assertEquals(4, p.bottom)
    }

    @Test fun perEdge() {
        val p = PaddingValues(1, 2, 3, 4)
        assertEquals(1, p.start); assertEquals(2, p.top); assertEquals(3, p.end); assertEquals(4, p.bottom)
    }
}

class IntSizeAndOffsetTest {
    @Test fun intSizePacks() {
        val s = IntSize(100, 50)
        assertEquals(100, s.width); assertEquals(50, s.height)
    }

    @Test fun intSizeNegative() {
        val s = IntSize(-3, -7)
        assertEquals(-3, s.width); assertEquals(-7, s.height)
    }

    @Test fun intOffsetAdds() {
        val a = IntOffset(10, 20)
        val b = IntOffset(3, 4)
        val sum = a + b
        assertEquals(13, sum.x); assertEquals(24, sum.y)
    }

    @Test fun intOffsetSubtracts() {
        val a = IntOffset(10, 20)
        val b = IntOffset(3, 4)
        val diff = a - b
        assertEquals(7, diff.x); assertEquals(16, diff.y)
    }
}
