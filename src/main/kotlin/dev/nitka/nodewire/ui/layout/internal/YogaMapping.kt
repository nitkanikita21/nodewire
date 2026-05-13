package dev.nitka.nodewire.ui.layout.internal

import dev.nitka.nodewire.ui.layout.Alignment
import dev.nitka.nodewire.ui.layout.Arrangement
import org.appliedenergistics.yoga.YogaAlign
import org.appliedenergistics.yoga.YogaGutter
import org.appliedenergistics.yoga.YogaJustify
import org.appliedenergistics.yoga.YogaNode
import org.appliedenergistics.yoga.style.StyleLength

/**
 * Trivial mappings from Nodewire's user-facing enums to Yoga's. Lives in
 * [internal] because users should never see Yoga types directly — they work
 * with [Arrangement] / [Alignment].
 *
 * [SpacedBy] is special: it sets gap + start-anchors children (FLEX_START
 * justify). Other arrangements distribute children explicitly with no gap.
 */
internal fun YogaNode.applyArrangement(arrangement: Arrangement) {
    when (arrangement) {
        Arrangement.Start -> setJustifyContent(YogaJustify.FLEX_START)
        Arrangement.Center -> setJustifyContent(YogaJustify.CENTER)
        Arrangement.End -> setJustifyContent(YogaJustify.FLEX_END)
        Arrangement.SpaceBetween -> setJustifyContent(YogaJustify.SPACE_BETWEEN)
        Arrangement.SpaceAround -> setJustifyContent(YogaJustify.SPACE_AROUND)
        Arrangement.SpaceEvenly -> setJustifyContent(YogaJustify.SPACE_EVENLY)
        is Arrangement.SpacedBy -> {
            setJustifyContent(YogaJustify.FLEX_START)
            setGap(YogaGutter.ALL, StyleLength.points(arrangement.space.toFloat()))
        }
    }
}

internal fun YogaNode.applyAlignment(alignment: Alignment) {
    setAlignItems(
        when (alignment) {
            Alignment.Start, Alignment.Top -> YogaAlign.FLEX_START
            Alignment.Center -> YogaAlign.CENTER
            Alignment.End, Alignment.Bottom -> YogaAlign.FLEX_END
            Alignment.Stretch -> YogaAlign.STRETCH
        }
    )
}
