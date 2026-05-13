package dev.nitka.nodewire.ui.input

/**
 * Pointer (mouse) events dispatched into the UI tree by [NwComposeScreen].
 * Coordinates are in screen-space (GUI pixels, top-left origin). Local
 * coordinates are computed by the hit tester right before invoking a handler.
 *
 * Buttons follow GLFW: `0 = left`, `1 = right`, `2 = middle`.
 */
sealed interface PointerEvent {
    val x: Int
    val y: Int

    data class Press(override val x: Int, override val y: Int, val button: Int) : PointerEvent
    data class Release(override val x: Int, override val y: Int, val button: Int) : PointerEvent
    data class Move(override val x: Int, override val y: Int) : PointerEvent
    data class Drag(
        override val x: Int,
        override val y: Int,
        val button: Int,
        val deltaX: Int,
        val deltaY: Int,
    ) : PointerEvent
    data class Scroll(
        override val x: Int,
        override val y: Int,
        val deltaY: Float,
    ) : PointerEvent
}
