package dev.nitka.nodewire.ui.modifier.input

import dev.nitka.nodewire.ui.core.InputModifierElement
import dev.nitka.nodewire.ui.core.Modifier
import dev.nitka.nodewire.ui.input.PointerEvent
import dev.nitka.nodewire.ui.input.PointerHandler

/**
 * Low-level pointer hook. Receives every pointer event that hits the node
 * (or that arrives via drag-focus routing). The handler decides what to
 * consume — return `true` to stop propagation, `false` to let other
 * handlers / parents see the same event.
 *
 * Use this for canvas-style drag, panning, custom gestures. For simple
 * "did the user click?" prefer [ClickModifier].
 */
class PointerInputModifier(
    val handler: (event: PointerEvent, localX: Int, localY: Int) -> Boolean,
) : InputModifierElement<PointerInputModifier>, PointerHandler {
    override fun mergeWith(other: PointerInputModifier) = other

    override fun handle(event: PointerEvent, localX: Int, localY: Int): Boolean =
        handler(event, localX, localY)
}

fun Modifier.pointerInput(
    handler: (event: PointerEvent, localX: Int, localY: Int) -> Boolean,
) = this then PointerInputModifier(handler)
