package dev.nitka.nodewire.ui.core

import dev.nitka.nodewire.ui.render.EmptyRenderer
import dev.nitka.nodewire.ui.render.Renderer
import org.appliedenergistics.yoga.YogaNode

/**
 * One node in the UI tree. Wraps a [YogaNode] 1:1 so the layout backend
 * mirrors our tree structure exactly. Compose's [NwApplier] adds/removes
 * children in both lists in lockstep.
 *
 * The [modifier] setter is where the chain is "compiled" for fast per-frame
 * access:
 *  - layout-affecting elements are applied directly to the YogaNode
 *  - style-affecting elements are cached in [styleModifiers] for the renderer
 *  - input-handling elements are cached in [inputModifiers] for the hit tester
 *
 * This bucketing happens once per modifier change (not per frame), so the
 * paint walk just iterates the pre-bucketed lists.
 */
class UiNode {
    val yoga: YogaNode = YogaNode()

    var modifier: Modifier = Modifier
        set(value) {
            field = value
            rebuildStyle()
        }

    /**
     * Yoga properties that come from the composable's own `Layout(yogaConfig = ...)`
     * lambda (Row's `justifyContent`/`gap`, Text's `measureFunc`, etc.) —
     * distinct from the modifier chain. We reset Yoga and re-apply both
     * whenever either input changes, so swapping a Row's arrangement
     * doesn't leave stale state in Yoga.
     */
    var yogaConfig: (org.appliedenergistics.yoga.YogaNode.() -> Unit) = {}
        set(value) {
            field = value
            rebuildStyle()
        }

    private fun rebuildStyle() {
        resetYogaStyle(yoga)
        val styles = mutableListOf<StyleModifierElement<*>>()
        val inputs = mutableListOf<InputModifierElement<*>>()
        modifier.foldIn(Unit) { _, element ->
            when (element) {
                is LayoutModifierElement<*> -> element.applyTo(yoga)
                is StyleModifierElement<*> -> styles.add(element)
                is InputModifierElement<*> -> inputs.add(element)
            }
        }
        styleModifiers = styles
        inputModifiers = inputs
        yoga.apply(yogaConfig)
    }

    var styleModifiers: List<StyleModifierElement<*>> = emptyList()
        private set
    var inputModifiers: List<InputModifierElement<*>> = emptyList()
        private set

    var renderer: Renderer = EmptyRenderer

    var parent: UiNode? = null
    val children: MutableList<UiNode> = mutableListOf()

    /** Layout result — valid only after the owner's `calculateLayout` for this frame. */
    val layoutX: Int get() = yoga.layoutX.toInt()
    val layoutY: Int get() = yoga.layoutY.toInt()
    val layoutWidth: Int get() = yoga.layoutWidth.toInt()
    val layoutHeight: Int get() = yoga.layoutHeight.toInt()

    override fun toString(): String =
        "UiNode(@${"%08x".format(System.identityHashCode(this))}, kids=${children.size})"
}
