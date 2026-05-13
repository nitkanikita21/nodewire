package dev.nitka.nodewire.ui.modifier.layout

import dev.nitka.nodewire.ui.core.LayoutModifierElement
import dev.nitka.nodewire.ui.core.Modifier
import org.appliedenergistics.yoga.YogaNode
import org.appliedenergistics.yoga.style.StyleSizeLength

data class SizeModifier(
    val width: Int? = null,
    val height: Int? = null,
) : LayoutModifierElement<SizeModifier> {
    override fun mergeWith(other: SizeModifier) =
        SizeModifier(other.width ?: width, other.height ?: height)

    override fun applyTo(yoga: YogaNode) {
        width?.let { yoga.setWidth(StyleSizeLength.points(it.toFloat())) }
        height?.let { yoga.setHeight(StyleSizeLength.points(it.toFloat())) }
    }
}

data class FillModifier(
    val width: Boolean = false,
    val height: Boolean = false,
) : LayoutModifierElement<FillModifier> {
    override fun mergeWith(other: FillModifier) =
        FillModifier(width || other.width, height || other.height)

    override fun applyTo(yoga: YogaNode) {
        if (width) yoga.setWidth(StyleSizeLength.percent(100f))
        if (height) yoga.setHeight(StyleSizeLength.percent(100f))
    }
}

fun Modifier.size(w: Int, h: Int) = this then SizeModifier(w, h)
fun Modifier.size(s: Int) = this then SizeModifier(s, s)
fun Modifier.width(w: Int) = this then SizeModifier(width = w)
fun Modifier.height(h: Int) = this then SizeModifier(height = h)

fun Modifier.fillMaxSize() = this then FillModifier(width = true, height = true)
fun Modifier.fillMaxWidth() = this then FillModifier(width = true)
fun Modifier.fillMaxHeight() = this then FillModifier(height = true)
