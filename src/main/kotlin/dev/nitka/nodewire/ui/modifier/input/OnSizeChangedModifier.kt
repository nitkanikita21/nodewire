package dev.nitka.nodewire.ui.modifier.input

import dev.nitka.nodewire.ui.core.InputModifierElement
import dev.nitka.nodewire.ui.core.Modifier
import dev.nitka.nodewire.ui.layout.IntSize

/**
 * Fires [callback] whenever the node's measured size changes between
 * frames. Dedup happens in [dev.nitka.nodewire.ui.core.NwUiOwner]'s
 * post-layout walk — it tracks last-seen size per UiNode in a map that
 * survives recomposition. The first frame after composition always fires
 * (the map starts empty for that node).
 *
 * The previous design kept `lastSize` as a field on this modifier, but
 * `Modifier.then` builds a new linked list every recompose so the field
 * reset to null on every change → callback fired on every recompose,
 * even when nothing about the layout actually changed.
 */
class OnSizeChangedModifier(
    val callback: (IntSize) -> Unit,
) : InputModifierElement<OnSizeChangedModifier>

fun Modifier.onSizeChanged(callback: (IntSize) -> Unit) =
    this then OnSizeChangedModifier(callback)
