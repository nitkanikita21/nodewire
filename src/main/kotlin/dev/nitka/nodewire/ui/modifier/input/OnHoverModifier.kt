package dev.nitka.nodewire.ui.modifier.input

import dev.nitka.nodewire.ui.core.InputModifierElement
import dev.nitka.nodewire.ui.core.Modifier
import dev.nitka.nodewire.ui.input.PointerEvent
import dev.nitka.nodewire.ui.input.PointerHandler

/**
 * Hover hook. [callback] fires with `true` when the pointer enters this
 * node's bounds and `false` when it leaves. Transition tracking lives in
 * [NwUiOwner.updateHover] which compares the set of currently-hovered
 * nodes against last frame — relying on a per-modifier flag would break
 * across recompositions that rebuild the modifier chain (a new instance
 * resets the flag and we'd miss the exit callback).
 */
class OnHoverModifier(
    val callback: (Boolean) -> Unit,
) : InputModifierElement<OnHoverModifier>, PointerHandler {
    override fun mergeWith(other: OnHoverModifier) = other

    /**
     * Never consumes. Move events propagate; NwUiOwner uses its own walk to
     * mark every nested hover modifier under the pointer (this `handle`
     * exists only so `OnHoverModifier` satisfies [PointerHandler] for
     * uniform iteration in the hit tester — Move never actually reaches
     * the click path).
     */
    override fun handle(event: PointerEvent, localX: Int, localY: Int): Boolean = false
}

fun Modifier.onHover(callback: (Boolean) -> Unit) = this then OnHoverModifier(callback)
