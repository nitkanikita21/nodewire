package dev.nitka.nodewire.client.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import dev.nitka.nodewire.graph.PinType
import dev.nitka.nodewire.ui.core.Modifier
import dev.nitka.nodewire.ui.core.UiNode
import dev.nitka.nodewire.ui.layout.Layout
import dev.nitka.nodewire.ui.modifier.layout.absolutePosition
import dev.nitka.nodewire.ui.modifier.layout.fillMaxSize
import com.mojang.math.Axis
import dev.nitka.nodewire.ui.render.Color
import dev.nitka.nodewire.ui.render.NwCanvas
import dev.nitka.nodewire.ui.render.Renderer
import dev.nitka.nodewire.ui.theme.NwTheme
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Paints connection wires between pin handles. Mounts inside [NodeCanvas]
 * as a transparent full-size sibling, so its renderer fires inside the
 * canvas's pan/zoom pose. Coordinates therefore come from
 * [PinPositions] in world-space and need no further transform here.
 *
 * Curve: cubic Bézier with horizontal control-point offsets — gives the
 * classic "S" shape that Blender, UE5, and similar editors use. Drawn as
 * many short rect samples (the canvas API has no native line/curve op);
 * sample density is constant in world units so curves stay smooth at any
 * zoom (visual zoom is applied by the canvas pose, not by us).
 *
 * Mount BEFORE node cards in the canvas content list — children paint in
 * insertion order, so wires render under the cards rather than across
 * their title bars.
 */
@Composable
fun WireLayer() {
    val editor = LocalEditorState.current ?: return
    // Capture pin colours from the theme inside the composable, then feed
    // them to a plain (non-@Composable) Renderer. Renderers run during
    // PaintWalk which is outside the composition phase — they can't read
    // CompositionLocals directly.
    val pinColors = NwTheme.colors.let { c ->
        mapOf(
            PinType.BOOL to c.pinBool,
            PinType.INT to c.pinInt,
            PinType.FLOAT to c.pinFloat,
            PinType.REDSTONE to c.pinRedstone,
            PinType.STRING to c.pinString,
            PinType.VEC2 to c.pinVec2,
            PinType.VEC3 to c.pinVec3,
            PinType.QUAT to c.pinQuat,
        )
    }
    val edges by editor.edges.collectAsState()
    val renderer = remember(editor, pinColors) { WireRenderer(editor, pinColors) }
    renderer.edges = edges
    Layout(
        modifier = Modifier.absolutePosition(0, 0).fillMaxSize(),
        renderer = renderer,
    )
}

private class WireRenderer(
    private val editor: EditorState,
    private val pinColors: Map<PinType, Color>,
) : Renderer {

    var edges: List<dev.nitka.nodewire.graph.Edge> = emptyList()

    override fun NwCanvas.render(node: UiNode) {
        val positions = editor.pinPositions
        for (edge in edges) {
            val from = positions.get(PinKey(edge.from.node, edge.from.pin, PinSide.Output)) ?: continue
            val to = positions.get(PinKey(edge.to.node, edge.to.pin, PinSide.Input)) ?: continue
            val fromNode = editor.nodeFlow(edge.from.node)?.value ?: continue
            val pinType = fromNode.outputs.firstOrNull { it.id == edge.from.pin }?.type ?: continue
            val color = pinColors[pinType] ?: continue
            drawBezier(from.first, from.second, to.first, to.second, color)
        }
        // Rubber-band wire: shown while a wire drag is in progress, from
        // either an output or an input pin. Drawn last so it stays on top
        // of existing wires.
        val src = editor.wireDragSource ?: return
        val srcPos = positions.get(src) ?: return
        val srcNode = editor.nodeFlow(src.node)?.value ?: return
        val srcType = when (src.side) {
            PinSide.Output -> srcNode.outputs.firstOrNull { it.id == src.pin }?.type
            PinSide.Input -> srcNode.inputs.firstOrNull { it.id == src.pin }?.type
        } ?: return
        val tempColor = pinColors[srcType] ?: return
        // Always feed output→input into drawBezier so the S-curve handles
        // point the right way regardless of which side the user grabbed.
        if (src.side == PinSide.Output) {
            drawBezier(srcPos.first, srcPos.second, editor.wireDragCursorX, editor.wireDragCursorY, tempColor)
        } else {
            drawBezier(editor.wireDragCursorX, editor.wireDragCursorY, srcPos.first, srcPos.second, tempColor)
        }
    }

    private fun NwCanvas.drawBezier(
        x0: Float, y0: Float,
        x3: Float, y3: Float,
        color: Color,
    ) {
        // Control-point horizontal offset = half the X gap, with a floor so
        // close-together pins still get a visible S curve instead of a
        // straight line.
        val handle = max(MIN_HANDLE_OFFSET, abs(x3 - x0) * 0.5f)
        val x1 = x0 + handle; val y1 = y0
        val x2 = x3 - handle; val y2 = y3

        // Constant segment count — each segment becomes a rotated rect via
        // pose, so length doesn't matter (a far-apart pair just gets longer
        // rectangles, never gaps). Adaptive sampling would only buy us
        // smoother curvature in tiny S-bends, not visible at typical scale.
        val segments = CURVE_SEGMENTS
        var prevX = x0; var prevY = y0
        for (i in 1..segments) {
            val t = i.toFloat() / segments
            val (px, py) = cubicBezier(t, x0, y0, x1, y1, x2, y2, x3, y3)
            drawLineSegment(prevX, prevY, px, py, color)
            prevX = px; prevY = py
        }
    }

    /**
     * Draws one straight segment as a rotated rectangle via the pose
     * matrix. No gaps regardless of length — replaces the point-stamp
     * approach that broke into dashes when world distance exceeded sample
     * count.
     */
    private fun NwCanvas.drawLineSegment(
        x0: Float, y0: Float,
        x1: Float, y1: Float,
        color: Color,
    ) {
        val dx = x1 - x0
        val dy = y1 - y0
        val len = sqrt(dx * dx + dy * dy)
        if (len < 0.01f) return
        val angle = atan2(dy, dx)
        val pose = gfx.pose()
        pose.pushPose()
        // offsetX/offsetY are zero inside the canvas (PaintWalk resets the
        // accumulator before entering the pose) but adding them explicitly
        // keeps this safe if the renderer ever moves outside a canvas.
        pose.translate(offsetX + x0, offsetY + y0, 0f)
        pose.mulPose(Axis.ZP.rotation(angle))
        // After rotation the +X axis points along the line and +Y is
        // perpendicular. Draw a thin horizontal bar of length `len`,
        // centered vertically on the line so the stroke is symmetric.
        val w = ceil(len).toInt()
        gfx.fill(0, -WIRE_HALF, w, WIRE_HALF + WIRE_THICKNESS % 2, color.argb)
        pose.popPose()
    }

    private fun cubicBezier(
        t: Float,
        x0: Float, y0: Float,
        x1: Float, y1: Float,
        x2: Float, y2: Float,
        x3: Float, y3: Float,
    ): Pair<Float, Float> {
        val u = 1f - t
        val uu = u * u
        val tt = t * t
        val uuu = uu * u
        val ttt = tt * t
        val x = uuu * x0 + 3 * uu * t * x1 + 3 * u * tt * x2 + ttt * x3
        val y = uuu * y0 + 3 * uu * t * y1 + 3 * u * tt * y2 + ttt * y3
        return x to y
    }

    companion object {
        private const val WIRE_THICKNESS = 2
        private const val WIRE_HALF = WIRE_THICKNESS / 2
        private const val MIN_HANDLE_OFFSET = 30f
        private const val CURVE_SEGMENTS = 32
    }
}
