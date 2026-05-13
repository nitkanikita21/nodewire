package dev.nitka.nodewire.ui.modifier.input

import dev.nitka.nodewire.ui.core.InputModifierElement
import dev.nitka.nodewire.ui.core.Modifier
import dev.nitka.nodewire.ui.layout.IntSize

/**
 * Fires [callback] whenever the node's measured size changes between
 * frames. NwUiOwner runs a post-layout walk per frame; each modifier
 * tracks its [lastSize] and only invokes the callback on actual change.
 *
 * The first frame after composition always fires (lastSize starts null).
 */
class OnSizeChangedModifier(
    val callback: (IntSize) -> Unit,
) : InputModifierElement<OnSizeChangedModifier> {
    var lastSize: IntSize? = null

    override fun mergeWith(other: OnSizeChangedModifier) = other
}

fun Modifier.onSizeChanged(callback: (IntSize) -> Unit) =
    this then OnSizeChangedModifier(callback)
