package dev.nitka.nodewire.ui.input.text

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TextFieldStateHolderTest {

    private fun holder(initial: String = "", caret: Int = 0): TextFieldStateHolder =
        TextFieldStateHolder(
            initial = TextFieldState(initial, TextRange.caret(caret)),
            clipboardGet = { "" },
            clipboardSet = {},
            nowMillis = { 0L },
        )

    @Test fun `initial state matches constructor input`() {
        val h = holder("hi", caret = 2)
        assertEquals("hi", h.state.text); assertEquals(2, h.state.caret)
    }

    @Test fun `insertString appends and notifies caret`() {
        val h = holder("ab", caret = 2)
        h.insertString("c")
        assertEquals("abc", h.state.text); assertEquals(TextRange.caret(3), h.state.selection)
    }

    @Test fun `undo restores previous state and redo replays`() {
        val h = holder("a", caret = 1)
        h.insertString("b")
        assertEquals("ab", h.state.text)
        h.undo()
        assertEquals("a", h.state.text)
        h.redo()
        assertEquals("ab", h.state.text)
    }

    @Test fun `redo stack cleared after fresh edit`() {
        val h = holder("a", caret = 1)
        h.insertString("b")
        h.undo()
        h.insertString("c")
        h.redo()  // nothing to redo
        assertEquals("ac", h.state.text)
    }

    @Test fun `replaceText resets undo branch`() {
        val h = holder("ab", caret = 2)
        h.insertString("c")
        h.replaceText("xyz")
        h.undo()  // should be no-op (undo cleared)
        assertEquals("xyz", h.state.text)
    }

    @Test fun `consecutive inserts within merge window collapse to one undo step`() {
        var now = 0L
        val h = TextFieldStateHolder(
            initial = TextFieldState(""),
            clipboardGet = { "" }, clipboardSet = {},
            nowMillis = { now },
        )
        h.insertString("a"); now += 100
        h.insertString("b"); now += 100
        h.insertString("c")
        assertEquals("abc", h.state.text)
        h.undo()
        assertEquals("", h.state.text)
    }

    @Test fun `insert after delete starts new undo step`() {
        var now = 0L
        val h = TextFieldStateHolder(
            initial = TextFieldState("ab", TextRange.caret(2)),
            clipboardGet = { "" }, clipboardSet = {},
            nowMillis = { now },
        )
        h.insertString("c"); now += 100
        h.backspace(); now += 100
        h.insertString("d")
        assertEquals("abd", h.state.text)
        h.undo(); assertEquals("ab", h.state.text)        // d
        h.undo(); assertEquals("abc", h.state.text)       // backspace
        h.undo(); assertEquals("ab", h.state.text)        // c
    }

    @Test fun `paste truncates at first newline`() {
        val h = TextFieldStateHolder(
            initial = TextFieldState("", TextRange.caret(0)),
            clipboardGet = { "first\nsecond" }, clipboardSet = {},
            nowMillis = { 0L },
        )
        h.pasteFromClipboard()
        assertEquals("first", h.state.text)
    }

    @Test fun `cut copies selection then deletes it`() {
        val written = StringBuilder()
        val h = TextFieldStateHolder(
            initial = TextFieldState("hello", TextRange(0, 5)),
            clipboardGet = { "" }, clipboardSet = { written.append(it) },
            nowMillis = { 0L },
        )
        h.cutToClipboard()
        assertEquals("hello", written.toString())
        assertEquals("", h.state.text)
    }

    @Test fun `selectAll selects whole text`() {
        val h = TextFieldStateHolder(
            initial = TextFieldState("hi", TextRange.caret(0)),
            clipboardGet = { "" }, clipboardSet = {},
            nowMillis = { 0L },
        )
        h.selectAll()
        assertEquals(TextRange(0, 2), h.state.selection)
    }

    @Test fun `mousePress sets caret and anchor`() {
        val h = TextFieldStateHolder(
            initial = TextFieldState("hello world"),
            clipboardGet = { "" }, clipboardSet = {},
            nowMillis = { 0L },
        )
        h.fontWidthOf = { it.length * 6 }
        h.paddingLeftPx = 0
        h.mousePress(localX = 18, shift = false, now = 1000L)
        assertEquals(TextRange.caret(3), h.state.selection)
    }

    @Test fun `mousePress with shift extends selection from existing anchor`() {
        val h = TextFieldStateHolder(
            initial = TextFieldState("hello world", TextRange.caret(2)),
            clipboardGet = { "" }, clipboardSet = {},
            nowMillis = { 0L },
        )
        h.fontWidthOf = { it.length * 6 }
        h.mousePress(localX = 36, shift = true, now = 1000L)
        assertEquals(TextRange(2, 6), h.state.selection)
    }

    @Test fun `double click selects word around caret`() {
        val h = TextFieldStateHolder(
            initial = TextFieldState("hello world"),
            clipboardGet = { "" }, clipboardSet = {},
            nowMillis = { 0L },
        )
        h.fontWidthOf = { it.length * 6 }
        h.mousePress(localX = 12, shift = false, now = 1000L)
        h.mousePress(localX = 12, shift = false, now = 1100L)
        assertEquals(TextRange(0, 5), h.state.selection)
    }

    @Test fun `triple click selects all`() {
        val h = TextFieldStateHolder(
            initial = TextFieldState("hello world"),
            clipboardGet = { "" }, clipboardSet = {},
            nowMillis = { 0L },
        )
        h.fontWidthOf = { it.length * 6 }
        h.mousePress(localX = 12, shift = false, now = 1000L)
        h.mousePress(localX = 12, shift = false, now = 1100L)
        h.mousePress(localX = 12, shift = false, now = 1200L)
        assertEquals(TextRange(0, 11), h.state.selection)
    }

    @Test fun `mouseDrag updates selection from anchor`() {
        val h = TextFieldStateHolder(
            initial = TextFieldState("hello world"),
            clipboardGet = { "" }, clipboardSet = {},
            nowMillis = { 0L },
        )
        h.fontWidthOf = { it.length * 6 }
        h.mousePress(localX = 6, shift = false, now = 1000L)
        h.mouseDrag(localX = 30)
        assertEquals(TextRange(1, 5), h.state.selection)
    }
}
