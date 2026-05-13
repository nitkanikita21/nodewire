package dev.nitka.nodewire.ui.input

/**
 * Mixed into [InputModifierElement]s that want to react to pointer events.
 * [HitTester] walks the input modifier list of each node and calls [handle]
 * on every handler in chain order. Returning `true` consumes the event and
 * stops propagation; `false` lets it bubble.
 *
 * [localX] / [localY] are in the node's local coordinates (top-left = 0,0).
 */
interface PointerHandler {
    fun handle(event: PointerEvent, localX: Int, localY: Int): Boolean
}
