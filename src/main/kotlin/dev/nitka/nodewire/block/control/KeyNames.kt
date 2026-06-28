package dev.nitka.nodewire.block.control

import org.lwjgl.glfw.GLFW

/**
 * English key labels for the Control Block UI. Vanilla's key display names are
 * localized to the game language (so they'd read in Ukrainian etc.); these stay
 * English and layout-independent (physical-key names), which is what a game
 * control wants.
 *
 * [NAMES] is the reliable source (a plain lookup, no GLFW call). GLFW's
 * `glfwGetKeyName` is only a fallback for the rare unmapped printable — it
 * returns null for every non-printable key (Insert/Delete/Home/numpad ops/…)
 * and is layout-dependent/occasionally flaky, which is why a binding sometimes
 * rendered as a raw "Key <code>".
 */
object KeyNames {

    fun label(code: Int): String = when {
        code < 0 -> "—"
        code >= Binding.MOUSE_BUTTON_BASE -> "Mouse ${code - Binding.MOUSE_BUTTON_BASE + 1}"
        NAMES.containsKey(code) -> NAMES.getValue(code)
        else -> GLFW.glfwGetKeyName(code, 0)?.takeIf { it.isNotBlank() }?.uppercase() ?: "Key $code"
    }

    private val NAMES: Map<Int, String> = buildMap {
        put(GLFW.GLFW_KEY_SPACE, "Space")
        put(GLFW.GLFW_KEY_ENTER, "Enter")
        put(GLFW.GLFW_KEY_TAB, "Tab")
        put(GLFW.GLFW_KEY_ESCAPE, "Esc")
        put(GLFW.GLFW_KEY_BACKSPACE, "Bksp")
        // Modifiers + system keys.
        put(GLFW.GLFW_KEY_LEFT_SHIFT, "L.Shift"); put(GLFW.GLFW_KEY_RIGHT_SHIFT, "R.Shift")
        put(GLFW.GLFW_KEY_LEFT_CONTROL, "L.Ctrl"); put(GLFW.GLFW_KEY_RIGHT_CONTROL, "R.Ctrl")
        put(GLFW.GLFW_KEY_LEFT_ALT, "L.Alt"); put(GLFW.GLFW_KEY_RIGHT_ALT, "R.Alt")
        put(GLFW.GLFW_KEY_LEFT_SUPER, "L.Super"); put(GLFW.GLFW_KEY_RIGHT_SUPER, "R.Super")
        put(GLFW.GLFW_KEY_MENU, "Menu")
        put(GLFW.GLFW_KEY_CAPS_LOCK, "Caps"); put(GLFW.GLFW_KEY_NUM_LOCK, "NumLk")
        put(GLFW.GLFW_KEY_SCROLL_LOCK, "ScrLk"); put(GLFW.GLFW_KEY_PRINT_SCREEN, "PrtSc")
        put(GLFW.GLFW_KEY_PAUSE, "Pause")
        // Arrows + navigation cluster.
        put(GLFW.GLFW_KEY_LEFT, "Left"); put(GLFW.GLFW_KEY_RIGHT, "Right")
        put(GLFW.GLFW_KEY_UP, "Up"); put(GLFW.GLFW_KEY_DOWN, "Down")
        put(GLFW.GLFW_KEY_INSERT, "Ins"); put(GLFW.GLFW_KEY_DELETE, "Del")
        put(GLFW.GLFW_KEY_HOME, "Home"); put(GLFW.GLFW_KEY_END, "End")
        put(GLFW.GLFW_KEY_PAGE_UP, "PgUp"); put(GLFW.GLFW_KEY_PAGE_DOWN, "PgDn")
        // Letters + digits: physical-key English names (layout-independent).
        for (c in 'A'..'Z') put(GLFW.GLFW_KEY_A + (c - 'A'), c.toString())
        for (n in 0..9) put(GLFW.GLFW_KEY_0 + n, n.toString())
        // US-layout symbol keys.
        put(GLFW.GLFW_KEY_MINUS, "-"); put(GLFW.GLFW_KEY_EQUAL, "=")
        put(GLFW.GLFW_KEY_LEFT_BRACKET, "["); put(GLFW.GLFW_KEY_RIGHT_BRACKET, "]")
        put(GLFW.GLFW_KEY_BACKSLASH, "\\"); put(GLFW.GLFW_KEY_SEMICOLON, ";")
        put(GLFW.GLFW_KEY_APOSTROPHE, "'"); put(GLFW.GLFW_KEY_GRAVE_ACCENT, "`")
        put(GLFW.GLFW_KEY_COMMA, ","); put(GLFW.GLFW_KEY_PERIOD, "."); put(GLFW.GLFW_KEY_SLASH, "/")
        // Function keys.
        for (f in 1..25) put(GLFW.GLFW_KEY_F1 + (f - 1), "F$f")
        // Numpad.
        for (n in 0..9) put(GLFW.GLFW_KEY_KP_0 + n, "KP$n")
        put(GLFW.GLFW_KEY_KP_ADD, "KP+"); put(GLFW.GLFW_KEY_KP_SUBTRACT, "KP-")
        put(GLFW.GLFW_KEY_KP_MULTIPLY, "KP*"); put(GLFW.GLFW_KEY_KP_DIVIDE, "KP/")
        put(GLFW.GLFW_KEY_KP_DECIMAL, "KP."); put(GLFW.GLFW_KEY_KP_ENTER, "KPEnt")
        put(GLFW.GLFW_KEY_KP_EQUAL, "KP=")
    }
}
