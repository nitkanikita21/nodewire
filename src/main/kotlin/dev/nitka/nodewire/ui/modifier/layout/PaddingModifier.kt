package dev.nitka.nodewire.ui.modifier.layout

import dev.nitka.nodewire.ui.core.LayoutModifierElement
import dev.nitka.nodewire.ui.core.Modifier
import dev.nitka.nodewire.ui.layout.PaddingValues
import org.appliedenergistics.yoga.YogaEdge
import org.appliedenergistics.yoga.YogaNode
import org.appliedenergistics.yoga.style.StyleLength

data class PaddingModifier(
    val start: Int = 0,
    val top: Int = 0,
    val end: Int = 0,
    val bottom: Int = 0,
) : LayoutModifierElement<PaddingModifier> {
    override fun mergeWith(other: PaddingModifier) = other // last-wins

    override fun applyTo(yoga: YogaNode) {
        yoga.setPadding(YogaEdge.LEFT, StyleLength.points(start.toFloat()))
        yoga.setPadding(YogaEdge.TOP, StyleLength.points(top.toFloat()))
        yoga.setPadding(YogaEdge.RIGHT, StyleLength.points(end.toFloat()))
        yoga.setPadding(YogaEdge.BOTTOM, StyleLength.points(bottom.toFloat()))
    }
}

fun Modifier.padding(all: Int) =
    this then PaddingModifier(all, all, all, all)

fun Modifier.padding(horizontal: Int = 0, vertical: Int = 0) =
    this then PaddingModifier(horizontal, vertical, horizontal, vertical)

fun Modifier.padding(start: Int = 0, top: Int = 0, end: Int = 0, bottom: Int = 0) =
    this then PaddingModifier(start, top, end, bottom)

fun Modifier.padding(values: PaddingValues) =
    this then PaddingModifier(values.start, values.top, values.end, values.bottom)
