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
     * index in the text. Binary search over substring widths.
     */
    fun pixelToCharIndex(localX: Int): Int {
        val px = (localX - paddingLeftPx + scrollXPx).coerceAtLeast(0)
        val text = state.text
        var lo = 0; var hi = text.length
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (fontWidthOf(text.substring(0, mid)) <= px) lo = mid else hi = mid - 1
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
}
