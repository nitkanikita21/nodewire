package dev.nitka.nodewire.ui.modifier.input

import dev.nitka.nodewire.ui.core.InputModifierElement
import dev.nitka.nodewire.ui.core.Modifier
import dev.nitka.nodewire.ui.layout.LayoutCoordinates

/**
 * Fires whenever the node's screen-space position or size changes between
 * frames. The callback receives a [LayoutCoordinates] snapshot — use it to
 * anchor popups (tooltip / dropdown / context menu) to a moving target.
 *
 * Dedup happens in [dev.nitka.nodewire.ui.core.NwUiOwner]'s post-layout
 * walk (per-UiNode map keyed by identity), not in this modifier instance —
 * see [OnSizeChangedModifier] for the rationale.
 */
class OnPositionedModifier(
    val callback: (LayoutCoordinates) -> Unit,
) : InputModifierElement<OnPositionedModifier>

fun Modifier.onPositioned(callback: (LayoutCoordinates) -> Unit) =
    this then OnPositionedModifier(callback)
