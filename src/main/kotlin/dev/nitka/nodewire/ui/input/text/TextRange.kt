package dev.nitka.nodewire.ui.input.text

/**
 * Half-open text range; `start` may equal `end` (a caret with no selection).
 * `start` is the anchor (where Shift-extend pivots from); `end` is the
 * caret (where the next character lands).
 */
data class TextRange(val start: Int, val end: Int) {
    val min: Int get() = minOf(start, end)
    val max: Int get() = maxOf(start, end)
    val length: Int get() = max - min
    val collapsed: Boolean get() = start == end

    companion object {
        val Zero = TextRange(0, 0)
        fun caret(at: Int) = TextRange(at, at)
    }
}
