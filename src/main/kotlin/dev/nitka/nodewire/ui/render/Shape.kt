package dev.nitka.nodewire.ui.render

sealed interface Shape

data object RectangleShape : Shape

/**
 * Rounded rectangle with per-corner radii in MC GUI pixels.
 * Use [RoundedCornerShape.invoke] for uniform corners.
 */
data class RoundedCornerShape(
    val radiusTopLeft: Int,
    val radiusTopRight: Int,
    val radiusBottomRight: Int,
    val radiusBottomLeft: Int,
) : Shape {
    companion object {
        operator fun invoke(radius: Int) = RoundedCornerShape(radius, radius, radius, radius)
    }
}
