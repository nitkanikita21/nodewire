package dev.nitka.nodewire.ui.layout

/**
 * Cross-axis alignment for Row/Column. Maps 1:1 to Yoga's `YogaAlign`.
 *
 * For a Row, `Vertical` is the cross axis. For a Column, `Horizontal` is the cross.
 */
sealed interface Alignment {
    sealed interface Horizontal : Alignment
    sealed interface Vertical : Alignment

    data object Start : Horizontal
    data object Top : Vertical

    /** Centered on its axis. Single object satisfies both Horizontal and Vertical. */
    data object Center : Horizontal, Vertical

    data object End : Horizontal
    data object Bottom : Vertical

    /** Stretch to fill the cross axis (Yoga's `YogaAlign.STRETCH`). */
    data object Stretch : Horizontal, Vertical
}
