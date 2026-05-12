package dev.nitka.nodewire.ui.core

import org.appliedenergistics.yoga.YogaAlign
import org.appliedenergistics.yoga.YogaDisplay
import org.appliedenergistics.yoga.YogaEdge
import org.appliedenergistics.yoga.YogaFlexDirection
import org.appliedenergistics.yoga.YogaGutter
import org.appliedenergistics.yoga.YogaJustify
import org.appliedenergistics.yoga.YogaNode
import org.appliedenergistics.yoga.YogaOverflow
import org.appliedenergistics.yoga.YogaPositionType
import org.appliedenergistics.yoga.YogaWrap
import org.appliedenergistics.yoga.style.StyleLength
import org.appliedenergistics.yoga.style.StyleSizeLength

/**
 * Reset a YogaNode's style to the same defaults a freshly-constructed node
 * would have. Called from [UiNode.modifier]'s setter before re-applying the
 * (potentially different) modifier chain, so removing a modifier actually
 * clears its prior effect on the underlying Yoga style.
 *
 * Yoga has no built-in `reset()` — we set every property we touch back to
 * its default value. Properties we never touch (locale, baseline) are not
 * reset. If a modifier in the future starts setting one of those, add it here.
 */
internal fun resetYogaStyle(yoga: YogaNode) {
    // Dimensions
    yoga.setWidth(StyleSizeLength.undefined())
    yoga.setHeight(StyleSizeLength.undefined())
    yoga.setMinWidth(StyleSizeLength.undefined())
    yoga.setMinHeight(StyleSizeLength.undefined())
    yoga.setMaxWidth(StyleSizeLength.undefined())
    yoga.setMaxHeight(StyleSizeLength.undefined())
    yoga.setAspectRatio(Float.NaN)

    // Flex
    yoga.setFlexDirection(YogaFlexDirection.COLUMN)
    yoga.setJustifyContent(YogaJustify.FLEX_START)
    yoga.setAlignItems(YogaAlign.STRETCH)
    yoga.setAlignContent(YogaAlign.FLEX_START)
    yoga.setWrap(YogaWrap.NO_WRAP)
    yoga.setFlexGrow(0f)
    yoga.setFlexShrink(0f)
    yoga.setFlexBasisAuto()

    // Position
    yoga.setPositionType(YogaPositionType.RELATIVE)
    for (edge in POSITION_EDGES) yoga.setPosition(edge, StyleLength.undefined())

    // Padding / margin / gap
    for (edge in ALL_EDGES) {
        yoga.setPadding(edge, StyleLength.points(0f))
        yoga.setMargin(edge, StyleLength.points(0f))
    }
    yoga.setGap(YogaGutter.ALL, StyleLength.points(0f))

    // Display / overflow
    yoga.setDisplay(YogaDisplay.FLEX)
    yoga.setOverflow(YogaOverflow.VISIBLE)
}

private val ALL_EDGES = arrayOf(
    YogaEdge.LEFT, YogaEdge.TOP, YogaEdge.RIGHT, YogaEdge.BOTTOM,
)
private val POSITION_EDGES = arrayOf(
    YogaEdge.LEFT, YogaEdge.TOP, YogaEdge.RIGHT, YogaEdge.BOTTOM,
    YogaEdge.START, YogaEdge.END,
)
