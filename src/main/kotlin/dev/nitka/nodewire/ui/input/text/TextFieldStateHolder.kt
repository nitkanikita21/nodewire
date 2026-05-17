package dev.nitka.nodewire.ui.input.text

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Stateful wrapper around [TextFieldState]. Owns the undo/redo stacks,
 * scroll offset, mouse-tracking bookkeeping, and dispatches edits via
 * [EditOps]. Constructed via `remember { ... }` inside a composable so
 * its identity persists across recomposition.
 *
 * Clipboard access is injected (defaults wire to MC's KeyboardHandler in
 * the composable) so tests don't pull in MC bootstrap.
 */
class TextFieldStateHolder(
    initial: TextFieldState = TextFieldState(),
    private val clipboardGet: () -> String = { "" },
    private val clipboardSet: (String) -> Unit = {},
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    var state: TextFieldState by mutableStateOf(initial)
        private set

    private val undoStack: ArrayDeque<TextFieldState> = ArrayDeque<TextFieldState>().apply { add(initial) }
    private val redoStack: ArrayDeque<TextFieldState> = ArrayDeque()
    private var lastUndoPushAt: Long = 0L
    private var lastUndoMergeable: Boolean = false

    private val mergeWindowMs: Long = 500L
    private val undoCapacity: Int = 100

    /**
     * Apply [produce] to current state and push onto undo. If [mergeable]
     * is true AND the previous push was also mergeable within
     * [mergeWindowMs], the new state overwrites the top of the undo stack
     * instead of pushing a fresh entry — keeps "type a sentence" into one
     * undo step.
     */
    fun mutate(mergeable: Boolean = false, produce: (TextFieldState) -> TextFieldState) {
        val next = produce(state)
        if (next == state) return
        val now = nowMillis()
        val canMerge = mergeable && lastUndoMergeable && (now - lastUndoPushAt) < mergeWindowMs
        if (canMerge && undoStack.isNotEmpty()) {
            undoStack[undoStack.size - 1] = next
        } else {
            undoStack.addLast(next)
            if (undoStack.size > undoCapacity) undoStack.removeFirst()
        }
        lastUndoPushAt = now
        lastUndoMergeable = mergeable
        redoStack.clear()
        state = next
    }

    fun insertString(s: String) = mutate(mergeable = true) { EditOps.insert(it, s) }

    /** Replace text entirely (external value reset). Clears undo as a fresh branch. */
    fun replaceText(text: String) {
        val newState = TextFieldState(text, TextRange.caret(text.length))
        state = newState
        undoStack.clear(); undoStack.addLast(newState)
        redoStack.clear()
        lastUndoMergeable = false
    }

    fun undo() {
        if (undoStack.size <= 1) return
        val current = undoStack.removeLast()
        redoStack.addLast(current)
        state = undoStack.last()
        lastUndoMergeable = false
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        val next = redoStack.removeLast()
        undoStack.addLast(next)
        state = next
        lastUndoMergeable = false
    }

    fun backspace() = mutate { EditOps.backspace(it) }
    fun deleteForward() = mutate { EditOps.deleteForward(it) }
    fun deleteWordBackward() = mutate { EditOps.deleteWordBackward(it) }
    fun deleteWordForward() = mutate { EditOps.deleteWordForward(it) }

    fun moveCaretBy(delta: Int, extend: Boolean) = mutate { EditOps.moveCaretBy(it, delta, extend) }
    fun moveCaretWord(direction: Int, extend: Boolean) = mutate { EditOps.moveCaretWord(it, direction, extend) }
    fun moveCaretToLineStart(extend: Boolean) = mutate { EditOps.moveCaretToLineStart(it, extend) }
    fun moveCaretToLineEnd(extend: Boolean) = mutate { EditOps.moveCaretToLineEnd(it, extend) }

    fun selectAll() = mutate { EditOps.selectAll(it) }

    fun copyToClipboard() {
        if (state.hasSelection) clipboardSet(state.text.substring(state.selection.min, state.selection.max))
    }

    fun cutToClipboard() {
        if (!state.hasSelection) return
        clipboardSet(state.text.substring(state.selection.min, state.selection.max))
        mutate { EditOps.deleteSelection(it) }
    }

    fun pasteFromClipboard() {
        val text = clipboardGet().substringBefore('\n')
        if (text.isEmpty()) return
        mutate { EditOps.insert(it, text) }
    }

    fun insertChar(codePoint: Int) {
        if (codePoint < 0x20 || codePoint == 0x7F) return
        insertString(Character.toChars(codePoint).concatToString())
    }

    /** Called when Enter is pressed; composable owns the actual callback. */
    var onSubmit: () -> Unit = {}
    fun submit() = onSubmit()

    /** Called when Esc is pressed; composable owns the actual focus controller. */
    var onReleaseFocus: () -> Unit = {}
    fun releaseFocus() = onReleaseFocus()

    // Set each frame by the composable so we can convert localX → char index.
    var fontWidthOf: (String) -> Int = { 0 }
    var paddingLeftPx: Int = 0
    var scrollXPx: Int = 0
    var visibleWidthPx: Int = 0

    private var selectionAnchor: Int = 0
    private var lastClickAt: Long = -10_000L
    private var lastClickIdx: Int = -1
    private var clickStreak: Int = 0

    private val doubleClickWindowMs: Long = 400L

    /**
     * Convert a local x (within the input's outer bounds) to a character
     * index in the text. Binary search over substring widths, then snap to
     * whichever boundary is closer — clicks past the midpoint of a glyph
     * land AFTER it, matching standard text-editor caret behaviour.
     */
    fun pixelToCharIndex(localX: Int): Int {
        val px = (localX - paddingLeftPx + scrollXPx).coerceAtLeast(0)
        val text = state.text
        var lo = 0; var hi = text.length
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (fontWidthOf(text.substring(0, mid)) <= px) lo = mid else hi = mid - 1
        }
        if (lo < text.length) {
            val leftEdge = fontWidthOf(text.substring(0, lo))
            val rightEdge = fontWidthOf(text.substring(0, lo + 1))
            if (px * 2 > leftEdge + rightEdge) return lo + 1
        }
        return lo
    }

    fun mousePress(localX: Int, shift: Boolean, now: Long) {
        val idx = pixelToCharIndex(localX)
        if (now - lastClickAt < doubleClickWindowMs && lastClickIdx == idx) {
            clickStreak++
        } else {
            clickStreak = 1
        }
        lastClickAt = now
        lastClickIdx = idx

        when (clickStreak) {
            1 -> {
                if (shift) {
                    state = state.copy(selection = TextRange(state.selection.start, idx))
                } else {
                    selectionAnchor = idx
                    state = state.copy(selection = TextRange.caret(idx))
                }
            }
            2 -> {
                val from = EditOps.wordBoundary(state.text, idx, -1)
                val to = EditOps.wordBoundary(state.text, idx, +1)
                selectionAnchor = from
                state = state.copy(selection = TextRange(from, to))
            }
            else -> {
                selectionAnchor = 0
                state = state.copy(selection = TextRange(0, state.text.length))
                clickStreak = 0  // reset so the next click starts fresh
            }
        }
    }

    fun mouseDrag(localX: Int) {
        val idx = pixelToCharIndex(localX)
        state = state.copy(selection = TextRange(selectionAnchor, idx))
    }

    var fontHeightPx: Int = 9

    /** Pixel x of the caret in text-local coordinates (NOT including paddingLeft). */
    fun caretPixelX(): Int = fontWidthOf(state.text.substring(0, state.caret))

    /** Pixel range of the selection in text-local coordinates, or null if collapsed. */
    fun selectionPixelRange(): IntRange? {
        val sel = state.selection
        if (sel.collapsed) return null
        val a = fontWidthOf(state.text.substring(0, sel.min))
        val b = fontWidthOf(state.text.substring(0, sel.max))
        return a..b
    }

    /** Scroll `scrollXPx` so caret stays within the visible region (with a small gutter). */
    fun ensureCaretVisible() {
        if (visibleWidthPx <= 0) return
        val caretX = caretPixelX()
        val gutter = 2
        val visibleStart = scrollXPx + gutter
        val visibleEnd = scrollXPx + visibleWidthPx - gutter
        if (caretX < visibleStart) scrollXPx = (caretX - gutter).coerceAtLeast(0)
        else if (caretX > visibleEnd) scrollXPx = caretX - visibleWidthPx + gutter
    }
}
