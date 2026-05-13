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
    /**
     * Drag deltas are [Float] (not Int) because MC's `mouseDragged` reports
     * sub-pixel motion. Truncating to Int loses fractional movement, which
     * compounds into visible drift when handlers later divide by zoom — a
     * 0.6 px drag becomes 0 in Int but should still nudge the world by
     * 0.6/zoom units.
     */
    data class Drag(
        override val x: Int,
        override val y: Int,
        val button: Int,
        val deltaX: Float,
        val deltaY: Float,
    ) : PointerEvent
    data class Scroll(
        override val x: Int,
        override val y: Int,
        val deltaY: Float,
    ) : PointerEvent
}

/**
 * Returns a copy of this event with new (x, y) coordinates, preserving the
 * subtype-specific fields. Used by HitTester / hover walk when descending
 * into a [NodeCanvas]: the world-coord transform produces fresh x/y but
 * deltas / button / scroll values must pass through unchanged.
 */
internal fun PointerEvent.withCoords(newX: Int, newY: Int): PointerEvent = when (this) {
    is PointerEvent.Press -> copy(x = newX, y = newY)
    is PointerEvent.Release -> copy(x = newX, y = newY)
    is PointerEvent.Move -> copy(x = newX, y = newY)
    is PointerEvent.Drag -> copy(x = newX, y = newY)
    is PointerEvent.Scroll -> copy(x = newX, y = newY)
}
