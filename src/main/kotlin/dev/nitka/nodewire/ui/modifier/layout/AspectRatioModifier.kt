package dev.nitka.nodewire.ui.modifier.layout

import dev.nitka.nodewire.ui.core.LayoutModifierElement
import dev.nitka.nodewire.ui.core.Modifier
import org.appliedenergistics.yoga.YogaNode

/** width / height. e.g. `aspectRatio(16f / 9f)` for widescreen. */
data class AspectRatioModifier(val ratio: Float) : LayoutModifierElement<AspectRatioModifier> {
    override fun mergeWith(other: AspectRatioModifier) = other

    override fun applyTo(yoga: YogaNode) {
        yoga.setAspectRatio(ratio)
    }
}

fun Modifier.aspectRatio(ratio: Float) = this then AspectRatioModifier(ratio)
