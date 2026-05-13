package dev.nitka.nodewire.ui.modifier.layout

import dev.nitka.nodewire.ui.core.LayoutModifierElement
import dev.nitka.nodewire.ui.core.Modifier
import org.appliedenergistics.yoga.YogaEdge
import org.appliedenergistics.yoga.YogaNode
import org.appliedenergistics.yoga.YogaPositionType
import org.appliedenergistics.yoga.style.StyleLength

/**
 * Translates a node by (x, y) relative to where the layout would place it.
 * Doesn't affect siblings or take it out of flow — Yoga's [RELATIVE] position.
 */
data class OffsetModifier(val x: Int, val y: Int) : LayoutModifierElement<OffsetModifier> {
    override fun mergeWith(other: OffsetModifier) = other

    override fun applyTo(yoga: YogaNode) {
        yoga.setPosition(YogaEdge.LEFT, StyleLength.points(x.toFloat()))
        yoga.setPosition(YogaEdge.TOP, StyleLength.points(y.toFloat()))
    }
}

/**
 * Places a node at absolute (x, y) within its parent's padding box. Removes
 * it from the flex flow — siblings layout as if this child didn't exist.
 */
data class AbsolutePositionModifier(val x: Int, val y: Int) : LayoutModifierElement<AbsolutePositionModifier> {
    override fun mergeWith(other: AbsolutePositionModifier) = other

    override fun applyTo(yoga: YogaNode) {
        yoga.setPositionType(YogaPositionType.ABSOLUTE)
        yoga.setPosition(YogaEdge.LEFT, StyleLength.points(x.toFloat()))
        yoga.setPosition(YogaEdge.TOP, StyleLength.points(y.toFloat()))
    }
}

fun Modifier.offset(x: Int, y: Int) = this then OffsetModifier(x, y)
fun Modifier.absolutePosition(x: Int, y: Int) = this then AbsolutePositionModifier(x, y)
