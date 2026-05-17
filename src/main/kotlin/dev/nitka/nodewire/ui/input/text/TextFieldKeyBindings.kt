package dev.nitka.nodewire.ui.input.text

import com.mojang.blaze3d.platform.InputConstants
import dev.nitka.nodewire.ui.input.KeyEvent
import org.lwjgl.glfw.GLFW

/**
 * Data-driven key bindings for [TextFieldStateHolder]. Each [KeyBinding]
 * maps a (keyCode, modifiers) pair to a holder action returning whether
 * it consumed the event.
 *
 * Modifier matching: bindings compare against the GLFW modifier bitmask
 * with [MOD_MASK] applied so lock keys (CAPS, NUM) don't break matches.
 */
data class KeyBinding(
    val keyCode: Int,
    val modifiers: Int = 0,
    val action: (TextFieldStateHolder) -> Boolean,
)

object TextFieldKeyBindings {
    private const val MOD_MASK = GLFW.GLFW_MOD_CONTROL or GLFW.GLFW_MOD_SHIFT or
        GLFW.GLFW_MOD_ALT or GLFW.GLFW_MOD_SUPER

    val DEFAULT: List<KeyBinding> = listOf(
        // Navigation
        KeyBinding(InputConstants.KEY_LEFT)                                                { it.moveCaretBy(-1, false); true },
        KeyBinding(InputConstants.KEY_LEFT, GLFW.GLFW_MOD_SHIFT)                           { it.moveCaretBy(-1, true);  true },
        KeyBinding(InputConstants.KEY_LEFT, GLFW.GLFW_MOD_CONTROL)                         { it.moveCaretWord(-1, false); true },
        KeyBinding(InputConstants.KEY_LEFT, GLFW.GLFW_MOD_CONTROL or GLFW.GLFW_MOD_SHIFT)  { it.moveCaretWord(-1, true); true },
        KeyBinding(InputConstants.KEY_RIGHT)                                               { it.moveCaretBy(+1, false); true },
        KeyBinding(InputConstants.KEY_RIGHT, GLFW.GLFW_MOD_SHIFT)                          { it.moveCaretBy(+1, true);  true },
        KeyBinding(InputConstants.KEY_RIGHT, GLFW.GLFW_MOD_CONTROL)                        { it.moveCaretWord(+1, false); true },
        KeyBinding(InputConstants.KEY_RIGHT, GLFW.GLFW_MOD_CONTROL or GLFW.GLFW_MOD_SHIFT) { it.moveCaretWord(+1, true); true },
        KeyBinding(InputConstants.KEY_HOME)                                                { it.moveCaretToLineStart(false); true },
        KeyBinding(InputConstants.KEY_HOME, GLFW.GLFW_MOD_SHIFT)                           { it.moveCaretToLineStart(true);  true },
        KeyBinding(InputConstants.KEY_END)                                                 { it.moveCaretToLineEnd(false); true },
        KeyBinding(InputConstants.KEY_END, GLFW.GLFW_MOD_SHIFT)                            { it.moveCaretToLineEnd(true);  true },
        // Edit
        KeyBinding(InputConstants.KEY_BACKSPACE)                                           { it.backspace(); true },
        KeyBinding(InputConstants.KEY_BACKSPACE, GLFW.GLFW_MOD_CONTROL)                    { it.deleteWordBackward(); true },
        KeyBinding(InputConstants.KEY_DELETE)                                              { it.deleteForward(); true },
        KeyBinding(InputConstants.KEY_DELETE, GLFW.GLFW_MOD_CONTROL)                       { it.deleteWordForward(); true },
        // Selection / clipboard
        KeyBinding(InputConstants.KEY_A, GLFW.GLFW_MOD_CONTROL)                            { it.selectAll(); true },
        KeyBinding(InputConstants.KEY_C, GLFW.GLFW_MOD_CONTROL)                            { it.copyToClipboard(); true },
        KeyBinding(InputConstants.KEY_X, GLFW.GLFW_MOD_CONTROL)                            { it.cutToClipboard(); true },
        KeyBinding(InputConstants.KEY_V, GLFW.GLFW_MOD_CONTROL)                            { it.pasteFromClipboard(); true },
        // Undo / redo
        KeyBinding(InputConstants.KEY_Z, GLFW.GLFW_MOD_CONTROL)                            { it.undo(); true },
        KeyBinding(InputConstants.KEY_Z, GLFW.GLFW_MOD_CONTROL or GLFW.GLFW_MOD_SHIFT)     { it.redo(); true },
        KeyBinding(InputConstants.KEY_Y, GLFW.GLFW_MOD_CONTROL)                            { it.redo(); true },
        // Submit / cancel
        KeyBinding(InputConstants.KEY_RETURN)                                              { it.submit(); true },
        KeyBinding(InputConstants.KEY_NUMPADENTER)                                         { it.submit(); true },
        KeyBinding(InputConstants.KEY_ESCAPE)                                              { it.releaseFocus(); true },
    )

    fun match(bindings: List<KeyBinding>, event: KeyEvent.Press): KeyBinding? {
        val mods = event.modifiers and MOD_MASK
        return bindings.firstOrNull { it.keyCode == event.keyCode && it.modifiers == mods }
    }
}
