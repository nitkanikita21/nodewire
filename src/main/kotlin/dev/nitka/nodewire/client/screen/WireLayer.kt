package dev.nitka.nodewire.client.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import dev.nitka.nodewire.graph.PinType
import dev.nitka.nodewire.ui.core.Modifier
import dev.nitka.nodewire.ui.core.UiNode
import dev.nitka.nodewire.ui.layout.Layout
import dev.nitka.nodewire.ui.modifier.input.pointerInput
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
    val labelBg = NwTheme.colors.surface
    val labelBorder = NwTheme.colors.border
    val labelText = NwTheme.colors.onSurface
    val edges by editor.edges.collectAsState()
    // Nodes hidden because they belong to a currently-collapsed group.
    // Re-computed on every change to the groups flow.
    val groupsValue by editor.groups.collectAsState()
    val hidden = remember(groupsValue) {
        if (groupsValue.isEmpty()) emptySet() else hiddenNodesFor(editor)
    }
    val renderer = remember(editor, pinColors, labelBg, labelBorder, labelText) {
        WireRenderer(editor, pinColors, labelBg, labelBorder, labelText)
    }
    renderer.edges = edges
    renderer.hiddenNodes = hidden
    Layout(
        modifier = Modifier.absolutePosition(0, 0).fillMaxSize(),
        renderer = renderer,
    )
    // Transparent hit overlay. Pressing within HIT_RADIUS world units of
    // any edge's midpoint sets `renamingEdge` — the overlay UI then
    // shows an inline TextInput.
    val edgesState = edges  // already captured via collectAsState above
    Layout(
        modifier = Modifier
            .absolutePosition(0, 0)
            .fillMaxSize()
            .pointerInput { ev, x, y ->
                if (ev !is dev.nitka.nodewire.ui.input.PointerEvent.Press) return@pointerInput false
                if (ev.button != 0) return@pointerInput false  // LMB only
                val positions = editor.pinPositions
                for (e in edgesState) {
                    // Same skip as the renderer: a wire fully internal to a
                    // collapsed group has no visible midpoint, so picking
                    // it from a stale pinPosition would let the user "label
                    // a wire" that doesn't appear on screen.
                    if (e.from.node in hidden && e.to.node in hidden) continue
                    val from = positions.get(PinKey(e.from.node, e.from.pin, PinSide.Output)) ?: continue
                    val to = positions.get(PinKey(e.to.node, e.to.pin, PinSide.Input)) ?: continue
                    val midX = (from.first + to.first) * 0.5f
                    val midY = (from.second + to.second) * 0.5f
                    val dx = x.toFloat() - midX
                    val dy = y.toFloat() - midY
                    if (dx * dx + dy * dy < HIT_RADIUS_SQ) {
                        editor.renamingEdge = e
                        return@pointerInput true
                    }
                }
                false
            },
        renderer = NoopRenderer,
    )
}

private const val HIT_RADIUS = 8f
private const val HIT_RADIUS_SQ = HIT_RADIUS * HIT_RADIUS

private object NoopRenderer : dev.nitka.nodewire.ui.render.Renderer {
    override fun dev.nitka.nodewire.ui.render.NwCanvas.render(node: dev.nitka.nodewire.ui.core.UiNode) { /* invisible */ }
}

private class WireRenderer(
    private val editor: EditorState,
    private val pinColors: Map<PinType, Color>,
    private val labelBg: Color,
    private val labelBorder: Color,
    private val labelText: Color,
) : Renderer {

    var edges: List<dev.nitka.nodewire.graph.Edge> = emptyList()
    /**
     * Nodes that belong to a currently-collapsed group and therefore have
     * no rendered NodeCard. An edge where BOTH endpoints are hidden is
     * fully internal to a collapsed group — no proxy pin can route it, so
     * it must not paint at all (otherwise its stale last-frame positions
     * draw an internal wire inside the collapsed tile).
     */
    var hiddenNodes: Set<dev.nitka.nodewire.graph.NodeId> = emptySet()

    override fun NwCanvas.render(node: UiNode) {
        val positions = editor.pinPositions
        for (edge in edges) {
            // Skip edges whose endpoint nodes no longer exist — defensive
            // against any path where _edges.value lags one frame behind
            // _nodes.value during a deletion. Without this check the
            // renderer would resolve a stale (last-frame) pin position
            // and draw a phantom wire to where the node used to be.
            val fromNode = editor.nodeFlow(edge.from.node)?.value ?: continue
            val toNode = editor.nodeFlow(edge.to.node)?.value ?: continue
            // Skip fully-internal edges of a collapsed group — both ends
            // are hidden, no proxy pin exists, and the stale positions
            // would otherwise paint a wire across the collapsed tile.
            if (edge.from.node in hiddenNodes && edge.to.node in hiddenNodes) continue
            val from = positions.get(PinKey(edge.from.node, edge.from.pin, PinSide.Output)) ?: continue
            val to = positions.get(PinKey(edge.to.node, edge.to.pin, PinSide.Input)) ?: continue
            // Also drop edges whose pin id no longer exists on the node —
            // happens after a node's pins are reshaped (vec_op dim change,
            // changeAeroChannel) and the edge wasn't yet cleaned up.
            if (fromNode.outputs.none { it.id == edge.from.pin }) continue
            if (toNode.inputs.none { it.id == edge.to.pin }) continue
            val pinType = fromNode.outputs.firstOrNull { it.id == edge.from.pin }?.type ?: continue
            val color = pinColors[pinType] ?: continue
            drawBezier(from.first, from.second, to.first, to.second, color)
            val label = edge.label
            if (!label.isNullOrBlank()) {
                val midX = ((from.first + to.first) * 0.5f).toInt()
                val midY = ((from.second + to.second) * 0.5f).toInt()
                val textW = font.width(label)
                // No background fill / border — text floats above the wire,
                // tight to its own width.
                drawText(label, midX - textW / 2, midY - font.lineHeight / 2, labelText)
            }
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
        pose.translate(offsetX + x0, offsetY + y0, 0f)
        pose.mulPose(Axis.ZP.rotation(angle))
        // After rotation +X is along the line and +Y is perpendicular.
        // Draw a thin horizontal bar of length `len`, centered on the
        // line. To anti-alias the (rotated) edge we sandwich the opaque
        // core between two 1-px feather strips at reduced alpha — fakes
        // GL_LINE_SMOOTH for `gfx.fill`-style hard-pixel quads.
        val w = ceil(len).toInt()
        val core = color.argb
        val edge = fadeAlpha(core, EDGE_ALPHA)
        // Top feather (1 px above core)
        gfx.fill(0, -WIRE_HALF - 1, w, -WIRE_HALF, edge)
        // Opaque core
        gfx.fill(0, -WIRE_HALF, w, WIRE_HALF + WIRE_THICKNESS % 2, core)
        // Bottom feather (1 px below core)
        gfx.fill(0, WIRE_HALF + WIRE_THICKNESS % 2, w, WIRE_HALF + WIRE_THICKNESS % 2 + 1, edge)
        pose.popPose()
    }

    /** Replace the alpha channel of [argb] with [alpha] (0..255). */
    private fun fadeAlpha(argb: Int, alpha: Int): Int {
        val srcA = (argb ushr 24) and 0xFF
        // Premultiply so a partially-transparent source wire still
        // produces a strictly-fainter feather, never a brighter one.
        val outA = (srcA * alpha) / 255
        return (outA shl 24) or (argb and 0x00FFFFFF)
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
        // More segments → finer chord approximation. 32 was already
        // smooth at typical zoom; bumping to 48 helps when the user
        // zooms in and the under-sampling becomes visible as facets.
        private const val CURVE_SEGMENTS = 48
        // Feather strip alpha (0..255). 96 ≈ 38% — strong enough to
        // soften the rotated edge, low enough not to read as a thicker
        // wire at rest.
        private const val EDGE_ALPHA = 96
    }
}
