package dev.nitka.nodewire.ui.modifier.input

import dev.nitka.nodewire.ui.core.InputModifierElement
import dev.nitka.nodewire.ui.core.Modifier
import dev.nitka.nodewire.ui.input.PointerEvent
import dev.nitka.nodewire.ui.input.PointerHandler

/**
 * Fires [onClick] on left-button [PointerEvent.Press] inside the node. Other
 * buttons fall through. Consumes the event by default — set [consume]=false
 * to let it bubble (useful for layered UI where multiple handlers care).
 */
data class ClickModifier(
    val onClick: () -> Unit,
    val consume: Boolean = true,
) : InputModifierElement<ClickModifier>, PointerHandler {
    override fun mergeWith(other: ClickModifier) = other

    override fun handle(event: PointerEvent, localX: Int, localY: Int): Boolean {
        if (event is PointerEvent.Press && event.button == 0) {
            onClick()
            return consume
        }
        return false
    }
}

fun Modifier.clickable(onClick: () -> Unit) = this then ClickModifier(onClick)
