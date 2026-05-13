package dev.nitka.nodewire.ui.modifier.layout

import dev.nitka.nodewire.ui.core.LayoutModifierElement
import dev.nitka.nodewire.ui.core.Modifier
import org.appliedenergistics.yoga.YogaEdge
import org.appliedenergistics.yoga.YogaNode
import org.appliedenergistics.yoga.style.StyleLength

data class MarginModifier(
    val start: Int = 0,
    val top: Int = 0,
    val end: Int = 0,
    val bottom: Int = 0,
) : LayoutModifierElement<MarginModifier> {
    override fun mergeWith(other: MarginModifier) = other

    override fun applyTo(yoga: YogaNode) {
        yoga.setMargin(YogaEdge.LEFT, StyleLength.points(start.toFloat()))
        yoga.setMargin(YogaEdge.TOP, StyleLength.points(top.toFloat()))
        yoga.setMargin(YogaEdge.RIGHT, StyleLength.points(end.toFloat()))
        yoga.setMargin(YogaEdge.BOTTOM, StyleLength.points(bottom.toFloat()))
    }
}

fun Modifier.margin(all: Int) =
    this then MarginModifier(all, all, all, all)

fun Modifier.margin(horizontal: Int = 0, vertical: Int = 0) =
    this then MarginModifier(horizontal, vertical, horizontal, vertical)

fun Modifier.margin(start: Int = 0, top: Int = 0, end: Int = 0, bottom: Int = 0) =
    this then MarginModifier(start, top, end, bottom)
