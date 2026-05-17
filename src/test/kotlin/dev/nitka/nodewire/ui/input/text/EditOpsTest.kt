package dev.nitka.nodewire.ui.input.text

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class EditOpsTest {
    @Test fun `TextRange min max work regardless of direction`() {
        val r1 = TextRange(3, 7)
        val r2 = TextRange(7, 3)
        assertEquals(3, r1.min); assertEquals(7, r1.max); assertEquals(4, r1.length)
        assertEquals(3, r2.min); assertEquals(7, r2.max); assertEquals(4, r2.length)
        assertFalse(r1.collapsed); assertFalse(r2.collapsed)
    }

    @Test fun `TextRange caret() builds collapsed range at position`() {
        val r = TextRange.caret(5)
        assertEquals(5, r.start); assertEquals(5, r.end); assertTrue(r.collapsed); assertEquals(0, r.length)
    }

    @Test fun `TextFieldState caret returns selection end`() {
        val s = TextFieldState("hello", TextRange(2, 4))
        assertEquals(4, s.caret); assertTrue(s.hasSelection)
    }
}
