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

    @Test fun `moveCaretBy clamps to text bounds`() {
        val s = TextFieldState("abc", TextRange.caret(1))
        assertEquals(TextRange.caret(0), EditOps.moveCaretBy(s, -5, extend = false).selection)
        assertEquals(TextRange.caret(3), EditOps.moveCaretBy(s, +5, extend = false).selection)
    }

    @Test fun `moveCaretBy with extend preserves anchor`() {
        val s = TextFieldState("abcdef", TextRange(2, 2))
        val r = EditOps.moveCaretBy(s, +2, extend = true)
        assertEquals(TextRange(2, 4), r.selection)
    }

    @Test fun `moveCaretBy without extend collapses selection toward direction`() {
        val s = TextFieldState("abcdef", TextRange(2, 5))
        val r = EditOps.moveCaretBy(s, +1, extend = false)
        assertEquals(TextRange.caret(6), r.selection)
    }

    @Test fun `wordBoundary forward skips whitespace into word`() {
        assertEquals(6, EditOps.wordBoundary("hello world", 5, +1))
    }

    @Test fun `wordBoundary forward stops at end of word`() {
        assertEquals(5, EditOps.wordBoundary("hello world", 0, +1))
    }

    @Test fun `wordBoundary backward stops at start of word`() {
        assertEquals(6, EditOps.wordBoundary("hello world", 11, -1))
    }

    @Test fun `wordBoundary on empty returns 0`() {
        assertEquals(0, EditOps.wordBoundary("", 0, +1))
        assertEquals(0, EditOps.wordBoundary("", 0, -1))
    }

    @Test fun `moveCaretWord advances by one word`() {
        val s = TextFieldState("foo bar baz", TextRange.caret(0))
        assertEquals(TextRange.caret(3), EditOps.moveCaretWord(s, +1, extend = false).selection)
    }

    @Test fun `moveCaretToLineStart and End collapse caret`() {
        val s = TextFieldState("hello", TextRange.caret(2))
        assertEquals(TextRange.caret(0), EditOps.moveCaretToLineStart(s, extend = false).selection)
        assertEquals(TextRange.caret(5), EditOps.moveCaretToLineEnd(s, extend = false).selection)
    }

    @Test fun `selectAll covers whole text`() {
        val s = TextFieldState("hello", TextRange.caret(2))
        assertEquals(TextRange(0, 5), EditOps.selectAll(s).selection)
    }

    @Test fun `deleteWordBackward removes to previous word boundary`() {
        val s = TextFieldState("foo bar baz", TextRange.caret(7))
        val r = EditOps.deleteWordBackward(s)
        assertEquals("foo  baz", r.text); assertEquals(TextRange.caret(4), r.selection)
    }

    @Test fun `deleteWordForward removes to next word boundary`() {
        val s = TextFieldState("foo bar baz", TextRange.caret(0))
        val r = EditOps.deleteWordForward(s)
        assertEquals(" bar baz", r.text); assertEquals(TextRange.caret(0), r.selection)
    }

    @Test fun `deleteWordBackward deletes selection if present`() {
        val s = TextFieldState("abcdef", TextRange(1, 4))
        val r = EditOps.deleteWordBackward(s)
        assertEquals("aef", r.text); assertEquals(TextRange.caret(1), r.selection)
    }
}
