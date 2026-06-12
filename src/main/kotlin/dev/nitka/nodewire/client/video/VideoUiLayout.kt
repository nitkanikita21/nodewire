package dev.nitka.nodewire.client.video

import dev.nitka.nodewire.script.VideoCanvas
import dev.nitka.nodewire.script.ui.Align
import dev.nitka.nodewire.script.ui.Justify
import dev.nitka.nodewire.script.ui.UiKind
import dev.nitka.nodewire.script.ui.UiSpec
import org.appliedenergistics.yoga.YogaAlign
import org.appliedenergistics.yoga.YogaEdge
import org.appliedenergistics.yoga.YogaFlexDirection
import org.appliedenergistics.yoga.YogaGutter
import org.appliedenergistics.yoga.YogaJustify
import org.appliedenergistics.yoga.YogaMeasureMode
import org.appliedenergistics.yoga.YogaNode
import org.appliedenergistics.yoga.YogaSize
import org.appliedenergistics.yoga.style.StyleLength
import org.appliedenergistics.yoga.style.StyleSizeLength

/**
 * Flexbox engine behind the script-facing `ui {}` DSL: mirrors a pure-data
 * [UiSpec] tree into Yoga nodes, lays it out at the canvas size and paints
 * via the [VideoCanvas] verbs (which clamp every coordinate).
 *
 * Lives on the CLIENT, on the mod side of the sandbox: scripts hand the spec
 * across [VideoCanvas.renderUi] and never link Yoga. Immediate-mode — the
 * tree is rebuilt per draw call; HUD-scale trees (tens of nodes) are far
 * below any allocation budget that matters at the video redraw cadence.
 */
object VideoUiLayout {

    fun render(canvas: VideoCanvas, root: UiSpec) {
        val rootNode = build(canvas, root)
        rootNode.setWidth(StyleSizeLength.points(canvas.width().toFloat()))
        rootNode.setHeight(StyleSizeLength.points(canvas.height().toFloat()))
        rootNode.calculateLayout(canvas.width().toFloat(), canvas.height().toFloat())
        paint(canvas, root, rootNode, 0, 0)
    }

    /** Recursively mirror the spec into a configured YogaNode tree. */
    private fun build(canvas: VideoCanvas, spec: UiSpec): YogaNode {
        val node = YogaNode()
        node.setFlexDirection(if (spec.vertical) YogaFlexDirection.COLUMN else YogaFlexDirection.ROW)
        node.setFlexGrow(spec.grow)
        node.setFlexShrink(spec.shrink)
        spec.width?.let { node.setWidth(StyleSizeLength.points(it.toFloat())) }
        spec.height?.let { node.setHeight(StyleSizeLength.points(it.toFloat())) }
        if (spec.pad > 0) {
            for (edge in PAD_EDGES) node.setPadding(edge, StyleLength.points(spec.pad.toFloat()))
        }
        if (spec.gap > 0) node.setGap(YogaGutter.ALL, StyleLength.points(spec.gap.toFloat()))
        node.setJustifyContent(
            when (spec.justify) {
                Justify.Start -> YogaJustify.FLEX_START
                Justify.Center -> YogaJustify.CENTER
                Justify.End -> YogaJustify.FLEX_END
                Justify.SpaceBetween -> YogaJustify.SPACE_BETWEEN
                Justify.SpaceAround -> YogaJustify.SPACE_AROUND
                Justify.SpaceEvenly -> YogaJustify.SPACE_EVENLY
            },
        )
        node.setAlignItems(
            when (spec.align) {
                Align.Start -> YogaAlign.FLEX_START
                Align.Center -> YogaAlign.CENTER
                Align.End -> YogaAlign.FLEX_END
                Align.Stretch -> YogaAlign.STRETCH
            },
        )

        if (spec.kind == UiKind.TEXT) {
            val w = canvas.textWidth(spec.text ?: "").toFloat()
            val h = canvas.lineHeight().toFloat()
            node.setMeasureFunction { _, availW, widthMode, _, _ ->
                val width = when (widthMode) {
                    YogaMeasureMode.EXACTLY -> availW
                    YogaMeasureMode.AT_MOST -> minOf(w, availW)
                    else -> w
                }
                YogaSize(width, h)
            }
        } else {
            for ((i, child) in spec.children.withIndex()) {
                node.addChildAt(build(canvas, child), i)
            }
        }
        return node
    }

    /** Depth-first paint: bg → content → children → border. */
    private fun paint(canvas: VideoCanvas, spec: UiSpec, node: YogaNode, ox: Int, oy: Int) {
        val x = ox + node.layoutX.toInt()
        val y = oy + node.layoutY.toInt()
        val w = node.layoutWidth.toInt()
        val h = node.layoutHeight.toInt()
        if (spec.bg != 0L) canvas.rect(x, y, w, h, spec.bg)
        when (spec.kind) {
            UiKind.TEXT -> spec.text?.let {
                canvas.text(
                    it,
                    x + node.getLayoutPadding(YogaEdge.LEFT).toInt(),
                    y + node.getLayoutPadding(YogaEdge.TOP).toInt(),
                    spec.textColor,
                )
            }
            UiKind.IMAGE -> spec.video?.let { if (w > 0 && h > 0) canvas.image(it, x, y, w, h) }
            UiKind.CONTAINER -> Unit
        }
        if (spec.kind != UiKind.TEXT) {
            for ((i, child) in spec.children.withIndex()) {
                paint(canvas, child, node.getChild(i), x, y)
            }
        }
        if (spec.borderColor != 0L && spec.borderW > 0) canvas.border(x, y, w, h, spec.borderW, spec.borderColor)
    }

    private val PAD_EDGES = arrayOf(YogaEdge.LEFT, YogaEdge.TOP, YogaEdge.RIGHT, YogaEdge.BOTTOM)
}
