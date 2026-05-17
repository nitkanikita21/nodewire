package dev.nitka.nodewire.ui.input.text

/**
 * Pure functions on [TextFieldState]. Each returns a new state; no MC
 * dependencies — safe for unit tests. Caller (TextFieldStateHolder) is
 * responsible for clamping selection within text bounds AFTER any text
 * mutation that could leave caret out of range.
 */
object EditOps {
    /** Replace selection (or insert at caret) with [str]; caret lands after inserted text. */
    fun insert(state: TextFieldState, str: String): TextFieldState {
        val s = state.selection
        val newText = state.text.substring(0, s.min) + str + state.text.substring(s.max)
        val caretAt = s.min + str.length
        return TextFieldState(newText, TextRange.caret(caretAt))
    }

    /** Drop the selected text; caret collapses to selection.min. No-op if collapsed. */
    fun deleteSelection(state: TextFieldState): TextFieldState {
        if (state.selection.collapsed) return state
        val s = state.selection
        val newText = state.text.substring(0, s.min) + state.text.substring(s.max)
        return TextFieldState(newText, TextRange.caret(s.min))
    }

    /** Delete selection if present; otherwise remove the char before caret. */
    fun backspace(state: TextFieldState): TextFieldState {
        if (state.hasSelection) return deleteSelection(state)
        val caret = state.caret
        if (caret == 0) return state
        return TextFieldState(
            text = state.text.removeRange(caret - 1, caret),
            selection = TextRange.caret(caret - 1),
        )
    }

    /** Delete selection if present; otherwise remove the char at caret. */
    fun deleteForward(state: TextFieldState): TextFieldState {
        if (state.hasSelection) return deleteSelection(state)
        val caret = state.caret
        if (caret >= state.text.length) return state
        return TextFieldState(
            text = state.text.removeRange(caret, caret + 1),
            selection = TextRange.caret(caret),
        )
    }

    /** Move caret by [delta] chars, clamping to bounds. If [extend], keep anchor. */
    fun moveCaretBy(state: TextFieldState, delta: Int, extend: Boolean): TextFieldState {
        val newCaret = (state.caret + delta).coerceIn(0, state.text.length)
        return state.copy(selection = makeRange(state.selection.start, newCaret, extend))
    }

    /** Move caret to the nearest word boundary in [direction] (-1 or +1). */
    fun moveCaretWord(state: TextFieldState, direction: Int, extend: Boolean): TextFieldState {
        val newCaret = wordBoundary(state.text, state.caret, direction)
        return state.copy(selection = makeRange(state.selection.start, newCaret, extend))
    }

    fun moveCaretToLineStart(state: TextFieldState, extend: Boolean): TextFieldState =
        state.copy(selection = makeRange(state.selection.start, 0, extend))

    fun moveCaretToLineEnd(state: TextFieldState, extend: Boolean): TextFieldState =
        state.copy(selection = makeRange(state.selection.start, state.text.length, extend))

    fun selectAll(state: TextFieldState): TextFieldState =
        state.copy(selection = TextRange(0, state.text.length))

    /**
     * Word-boundary search using three character classes (whitespace, word,
     * other). Skips the class of the char at [from] (or before it, for
     * backward) and returns the first index where the class changes.
     */
    fun wordBoundary(text: String, from: Int, direction: Int): Int {
        if (text.isEmpty()) return 0
        return if (direction > 0) {
            if (from >= text.length) return text.length
            val startClass = classOf(text[from])
            var i = from
            while (i < text.length && classOf(text[i]) == startClass) i++
            i
        } else {
            if (from <= 0) return 0
            val startClass = classOf(text[from - 1])
            var i = from
            while (i > 0 && classOf(text[i - 1]) == startClass) i--
            i
        }
    }

    fun deleteWordBackward(state: TextFieldState): TextFieldState {
        if (state.hasSelection) return deleteSelection(state)
        val from = wordBoundary(state.text, state.caret, -1)
        if (from == state.caret) return state
        return TextFieldState(
            text = state.text.removeRange(from, state.caret),
            selection = TextRange.caret(from),
        )
    }

    fun deleteWordForward(state: TextFieldState): TextFieldState {
        if (state.hasSelection) return deleteSelection(state)
        val to = wordBoundary(state.text, state.caret, +1)
        if (to == state.caret) return state
        return TextFieldState(
            text = state.text.removeRange(state.caret, to),
            selection = TextRange.caret(state.caret),
        )
    }

    /** When extending, keep the existing anchor; otherwise collapse to caret. */
    private fun makeRange(anchor: Int, caret: Int, extend: Boolean): TextRange =
        if (extend) TextRange(anchor, caret) else TextRange.caret(caret)

    private enum class CharClass { Whitespace, Word, Other }
    private fun classOf(c: Char): CharClass = when {
        c.isWhitespace() -> CharClass.Whitespace
        c.isLetterOrDigit() -> CharClass.Word
        else -> CharClass.Other
    }
}
