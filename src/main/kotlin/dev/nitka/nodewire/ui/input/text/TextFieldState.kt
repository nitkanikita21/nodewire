package dev.nitka.nodewire.ui.input.text

/**
 * Immutable snapshot of a text-field's content + cursor position.
 * Mutated only via [EditOps] (pure functions that return a new state).
 */
data class TextFieldState(
    val text: String = "",
    val selection: TextRange = TextRange.Zero,
) {
    val caret: Int get() = selection.end
    val hasSelection: Boolean get() = !selection.collapsed
}
