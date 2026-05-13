package dev.nitka.nodewire.ui.modifier.style

import dev.nitka.nodewire.ui.core.Modifier
import dev.nitka.nodewire.ui.core.StyleModifierElement
import dev.nitka.nodewire.ui.render.Color
import dev.nitka.nodewire.ui.render.RectangleShape
import dev.nitka.nodewire.ui.render.Shape

data class BackgroundModifier(
    val color: Color,
    val shape: Shape = RectangleShape,
) : StyleModifierElement<BackgroundModifier> {
    override fun mergeWith(other: BackgroundModifier) = other // last-wins
}

fun Modifier.background(color: Color, shape: Shape = RectangleShape) =
    this then BackgroundModifier(color, shape)
