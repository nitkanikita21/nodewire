package dev.nitka.nodewire.ui.modifier.style

import dev.nitka.nodewire.ui.core.Modifier
import dev.nitka.nodewire.ui.core.StyleModifierElement

/**
 * Sibling paint-order override. Higher [value] paints later (on top).
 * Default for a node without this modifier is 0. The paint walk does a
 * stable sort of each parent's children by their effective zIndex before
 * recursing — so ordering ties preserve the composition source order.
 *
 * Not a layout modifier: zIndex affects only WHEN a node paints, not where
 * its box is computed. Useful for selected nodes in the editor (drag-up to
 * top), tooltips that live inline in the tree, or any "this should win
 * paint order against its sibling" case that doesn't warrant a full Popup.
 */
data class ZIndexModifier(val value: Int) : StyleModifierElement<ZIndexModifier> {
    override fun mergeWith(other: ZIndexModifier) = other // last-wins
}

fun Modifier.zIndex(value: Int) = this then ZIndexModifier(value)
