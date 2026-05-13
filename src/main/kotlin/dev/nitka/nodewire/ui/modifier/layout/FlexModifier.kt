package dev.nitka.nodewire.ui.modifier.layout

import dev.nitka.nodewire.ui.core.LayoutModifierElement
import dev.nitka.nodewire.ui.core.Modifier
import org.appliedenergistics.yoga.YogaNode

data class FlexModifier(
    val grow: Float = 0f,
    val shrink: Float = 0f,
    val basis: Int? = null,
) : LayoutModifierElement<FlexModifier> {
    override fun mergeWith(other: FlexModifier) = other

    override fun applyTo(yoga: YogaNode) {
        yoga.setFlexGrow(grow)
        yoga.setFlexShrink(shrink)
        if (basis != null) yoga.setFlexBasis(basis.toFloat()) else yoga.setFlexBasisAuto()
    }
}

/**
 * Compose-style `weight(w)` shortcut: grow proportionally to fill remaining
 * main-axis space. `weight(1f)` on two siblings → they split the space 50/50.
 * Sets basis to 0 so size hint doesn't count against the grow distribution.
 */
data class WeightModifier(val weight: Float) : LayoutModifierElement<WeightModifier> {
    override fun mergeWith(other: WeightModifier) = other

    override fun applyTo(yoga: YogaNode) {
        yoga.setFlexGrow(weight)
        yoga.setFlexBasis(0f)
    }
}

fun Modifier.flex(grow: Float = 0f, shrink: Float = 0f, basis: Int? = null) =
    this then FlexModifier(grow, shrink, basis)

fun Modifier.weight(weight: Float) = this then WeightModifier(weight)
