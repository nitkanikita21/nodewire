package dev.nitka.nodewire.ui.scroll

import dev.nitka.nodewire.ui.core.InputModifierElement
import dev.nitka.nodewire.ui.core.Modifier
import dev.nitka.nodewire.ui.input.PointerEvent
import dev.nitka.nodewire.ui.input.PointerHandler

/**
 * Axis of a [ScrollModifier]. Vertical is the common case (vertical lists,
 * settings panels); Horizontal exists for ribbons / wide tables / a
 * future node-canvas's pan-axis lock.
 */
enum class ScrollAxis { Vertical, Horizontal }

/**
 * Listens for [PointerEvent.Scroll] over a node and updates the wrapped
 * [ScrollState]. The framework also reads this modifier during paint to
 * shift children by `-state.value` along [axis], and during post-layout
 * to recompute `state.maxValue` from the children's combined size.
 *
 * One scroll axis per modifier — to make a container scroll on both axes,
 * chain `.verticalScroll(vState).horizontalScroll(hState)`.
 *
 * [reverseDirection] flips the wheel-to-scroll mapping (useful for some
 * touchpad conventions or if the framework's default direction feels wrong).
 */
class ScrollModifier(
    val state: ScrollState,
    val axis: ScrollAxis,
    val pixelsPerNotch: Int = 12,
    val reverseDirection: Boolean = false,
) : InputModifierElement<ScrollModifier>, PointerHandler {

    override fun mergeWith(other: ScrollModifier) = other

    override fun handle(event: PointerEvent, localX: Int, localY: Int): Boolean {
        if (event !is PointerEvent.Scroll) return false
        val direction = if (reverseDirection) 1 else -1
        // MC scroll: +deltaY = wheel up = scroll content up (= state.value down).
        state.scrollBy((event.deltaY * pixelsPerNotch.toFloat() * direction).toInt())
        return true
    }
}

fun Modifier.verticalScroll(state: ScrollState, reverseDirection: Boolean = false) =
    this then ScrollModifier(state, ScrollAxis.Vertical, reverseDirection = reverseDirection)

fun Modifier.horizontalScroll(state: ScrollState, reverseDirection: Boolean = false) =
    this then ScrollModifier(state, ScrollAxis.Horizontal, reverseDirection = reverseDirection)
