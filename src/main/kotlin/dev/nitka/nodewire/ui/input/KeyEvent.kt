package dev.nitka.nodewire.ui.input

/**
 * Keyboard events. [Press]/[Release] carry GLFW key + scan codes plus a
 * bitmask of modifiers (GLFW_MOD_SHIFT etc.). [Char] is a text-input event
 * with a code point — emitted by MC's IME path so we get composed text
 * naturally; don't try to derive text from key presses.
 */
sealed interface KeyEvent {
    data class Press(val keyCode: Int, val scanCode: Int, val modifiers: Int) : KeyEvent
    data class Release(val keyCode: Int, val scanCode: Int, val modifiers: Int) : KeyEvent
    data class Char(val codePoint: Int, val modifiers: Int) : KeyEvent
}
