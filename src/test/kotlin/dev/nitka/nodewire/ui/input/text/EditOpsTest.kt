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

    @Test fun `insert into empty state places caret after inserted text`() {
        val r = EditOps.insert(TextFieldState(), "abc")
        assertEquals("abc", r.text)
        assertEquals(TextRange.caret(3), r.selection)
    }

    @Test fun `insert at caret leaves text intact around it`() {
        val s = TextFieldState("ace", TextRange.caret(1))
        val r = EditOps.insert(s, "b")
        assertEquals("abce", r.text)
        assertEquals(TextRange.caret(2), r.selection)
    }

    @Test fun `insert replaces selection`() {
        val s = TextFieldState("axxxe", TextRange(1, 4))
        val r = EditOps.insert(s, "b")
        assertEquals("abe", r.text)
        assertEquals(TextRange.caret(2), r.selection)
    }

    @Test fun `deleteSelection removes selected range and collapses caret`() {
        val s = TextFieldState("hello world", TextRange(6, 11))
        val r = EditOps.deleteSelection(s)
        assertEquals("hello ", r.text)
        assertEquals(TextRange.caret(6), r.selection)
    }

    @Test fun `deleteSelection is a no-op when collapsed`() {
        val s = TextFieldState("abc", TextRange.caret(1))
        assertEquals(s, EditOps.deleteSelection(s))
    }

    @Test fun `backspace removes char before caret`() {
        val r = EditOps.backspace(TextFieldState("abc", TextRange.caret(2)))
        assertEquals("ac", r.text); assertEquals(TextRange.caret(1), r.selection)
    }

    @Test fun `backspace at caret 0 is no-op`() {
        val s = TextFieldState("abc", TextRange.caret(0))
        assertEquals(s, EditOps.backspace(s))
    }

    @Test fun `backspace with selection deletes selection only`() {
        val s = TextFieldState("abcdef", TextRange(1, 4))
        val r = EditOps.backspace(s)
        assertEquals("aef", r.text); assertEquals(TextRange.caret(1), r.selection)
    }

    @Test fun `deleteForward removes char at caret`() {
        val r = EditOps.deleteForward(TextFieldState("abc", TextRange.caret(1)))
        assertEquals("ac", r.text); assertEquals(TextRange.caret(1), r.selection)
    }

    @Test fun `deleteForward at end is no-op`() {
        val s = TextFieldState("abc", TextRange.caret(3))
        assertEquals(s, EditOps.deleteForward(s))
    }
}
