package dev.nitka.nodewire.ui.layout

/**
 * Main-axis arrangement for Row/Column. Maps 1:1 to Yoga's `YogaJustify`.
 *
 * `spacedBy(value)` is a fixed gap between children; implemented via Yoga's
 * `setGap(ALL, value)`. Children are placed start-anchored when using `spacedBy`.
 */
sealed interface Arrangement {
    sealed interface Horizontal : Arrangement
    sealed interface Vertical : Arrangement

    data object Start : Horizontal, Vertical
    data object Center : Horizontal, Vertical
    data object End : Horizontal, Vertical
    data object SpaceBetween : Horizontal, Vertical
    data object SpaceAround : Horizontal, Vertical
    data object SpaceEvenly : Horizontal, Vertical

    data class SpacedBy(val space: Int) : Horizontal, Vertical

    /**
     * End-anchored counterpart to [SpacedBy]. Items pack at the far end
     * of the main axis (flex-end) with [space] between each. Use when
     * a row's content should hug its right edge while still keeping a
     * gap between items (e.g. an output pin row whose handle sits on
     * the card's right edge and the chip/name should sit immediately
     * to its left).
     */
    data class SpacedByEnd(val space: Int) : Horizontal, Vertical

    companion object {
        /** Convenience to mirror Compose's `Arrangement.spacedBy(8.dp)` shape. */
        fun spacedBy(space: Int): SpacedBy = SpacedBy(space)
        fun spacedByEnd(space: Int): SpacedByEnd = SpacedByEnd(space)
    }
}
