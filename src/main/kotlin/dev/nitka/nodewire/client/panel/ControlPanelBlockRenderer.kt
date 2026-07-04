package dev.nitka.nodewire.client.panel

import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import dev.nitka.nodewire.block.ControlPanelBlock
import dev.nitka.nodewire.block.ControlPanelBlockEntity
import dev.nitka.nodewire.block.panel.PanelElements
import dev.nitka.nodewire.block.panel.PanelGrid
import dev.nitka.nodewire.block.panel.PlacedElement
import dev.nitka.nodewire.client.video.VideoBlit
import dev.nitka.nodewire.client.video.VideoManager
import dev.nitka.nodewire.item.ChannelLinkToolItem
import dev.nitka.nodewire.item.PanelElementItem
import dev.nitka.nodewire.item.PanelKeyItem
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.core.Direction
import net.minecraft.world.phys.BlockHitResult
import org.joml.Matrix4f
import kotlin.math.cos
import kotlin.math.sin

/**
 * First-pass procedural renderer for the [ControlPanelBlock]: draws the plate
 * background and every placed element as flat colour quads on the panel face,
 * with the live operated/driven value as a state overlay (toggle colour, slider
 * thumb, bar fill, lamp glow, …).
 *
 * Geometry is the exact inverse of [PanelGrid.hitToGrid] — grid `(u,v)` (top-left
 * origin, u right, v down) is un-spun and mapped back onto the panel's display
 * surface — so an element renders precisely where you click to hit its cell. The
 * surface sits 2px off the mounting wall (the [ControlPanelBlock] slab side);
 * plate / element / overlay get increasing outsets so coplanar quads don't
 * z-fight. Quads are double-sided and full-bright (HUD look).
 *
 * Reuses [VideoBlit.plainTypeFor] + a 1×1 white texture (white × vertex colour =
 * a solid colour quad). Baked bodies / textures / value text are a later slice.
 */
class ControlPanelBlockRenderer(
    @Suppress("UNUSED_PARAMETER") ctx: BlockEntityRendererProvider.Context,
) : BlockEntityRenderer<ControlPanelBlockEntity> {

    override fun render(
        be: ControlPanelBlockEntity,
        partialTick: Float,
        poseStack: PoseStack,
        buffers: MultiBufferSource,
        light: Int,
        overlay: Int,
    ) {
        // Never draw during a camera capture pass — the capture re-enters
        // renderLevel and an extra BER here breaks it (leaking the JOML modelview
        // stack → "max stack size of 16"). Same guard as ScreenBlockRenderer.
        if (VideoManager.isCapturing()) return

        val elements = be.elements()
        val face = be.blockState.getValue(ControlPanelBlock.FACE)
        val spin = be.blockState.getValue(ControlPanelBlock.SPIN)

        poseStack.pushPose()
        val matrix = poseStack.last().pose()
        val font = Minecraft.getInstance().font
        val consumer = buffers.getBuffer(VideoBlit.plainTypeFor(whiteTexId()))

        // All colour quads first, into a single held buffer. Video blits and text
        // are collected and drawn LAST: requesting another render type (or
        // font.drawInBatch) switches the MultiBufferSource's active buffer, which
        // would end this quad buffer mid-stream and crash with "Not building!".
        val texts = ArrayList<TextDraw>()
        val videos = ArrayList<VideoDraw>()
        rect(consumer, matrix, face, spin, 0.0, 0.0, 1.0, 1.0, COL_PLATE, OUT_PLATE)
        for (e in elements) drawElement(consumer, matrix, be, face, spin, e, texts, videos)
        drawGuide(consumer, matrix, be, face, spin)
        for (v in videos) drawVideo(matrix, buffers, face, spin, v)
        for (t in texts) drawText(matrix, buffers, font, face, spin, t.u0, t.v0, t.u1, t.v1, t.text, t.color)
        poseStack.popPose()
    }

    override fun getRenderBoundingBox(be: ControlPanelBlockEntity): net.minecraft.world.phys.AABB =
        net.minecraft.world.phys.AABB(be.blockPos)

    private fun drawElement(
        consumer: VertexConsumer,
        m: Matrix4f,
        be: ControlPanelBlockEntity,
        face: Direction,
        spin: Int,
        e: PlacedElement,
        texts: MutableList<TextDraw>,
        videos: MutableList<VideoDraw>,
    ) {
        if (PanelElements.byId(e.typeId) == null) return
        val gap = ELEMENT_GAP
        val u0 = (e.cellX + gap) / 16.0
        val v0 = (e.cellY + gap) / 16.0
        val u1 = (e.cellX + e.cols - gap) / 16.0
        val v1 = (e.cellY + e.rows - gap) / 16.0
        val cfg = e.config
        fun d(key: String, dflt: Double) = if (cfg.contains(key)) cfg.getDouble(key) else dflt
        fun col(key: String, dflt: Int) = if (cfg.contains(key)) cfg.getInt(key) else dflt
        fun body(argb: Int) = rect(consumer, m, face, spin, u0, v0, u1, v1, argb, OUT_BODY)
        fun over(a: Double, b: Double, c: Double, dd: Double, argb: Int) =
            rect(consumer, m, face, spin, a, b, c, dd, argb, OUT_OVER)
        val on = e.value != 0.0

        when (e.typeId) {
            "toggle" -> body(if (on) COL_ON else COL_OFF)
            "momentary" -> body(if (on) COL_PRESS else COL_BTN)
            "selector" -> {
                body(COL_BODY)
                val positions = d("positions", 2.0).toInt().coerceAtLeast(1)
                val idx = e.value.toInt().coerceIn(0, positions - 1)
                val segW = (u1 - u0) / positions
                val mu0 = u0 + idx * segW
                over(mu0, v0, mu0 + segW, v0 + (v1 - v0) * 0.25, COL_MARK)
            }
            "slider" -> {
                body(COL_TRACK)
                val frac = norm(e.value, d("min", 0.0), d("max", 1.0))
                if (e.cols >= e.rows) {
                    val tw = (u1 - u0) * 0.12
                    val tx = (u0 + frac * (u1 - u0)).coerceIn(u0 + tw / 2, u1 - tw / 2)
                    over(tx - tw / 2, v0, tx + tw / 2, v1, COL_THUMB)
                } else {
                    val th = (v1 - v0) * 0.12
                    val ty = (v1 - frac * (v1 - v0)).coerceIn(v0 + th / 2, v1 - th / 2)
                    over(u0, ty - th / 2, u1, ty + th / 2, COL_THUMB)
                }
            }
            "knob" -> {
                body(COL_BODY)
                val frac = norm(e.value, d("min", 0.0), d("max", 1.0))
                val ang = Math.toRadians(frac * d("sweep", 270.0))
                val cu = (u0 + u1) / 2; val cv = (v0 + v1) / 2
                val r = minOf(u1 - u0, v1 - v0) * 0.35
                val mu = cu + sin(ang) * r; val mv = cv + cos(ang) * r // 0° = straight down (+v)
                val s = (u1 - u0) * 0.10
                over(mu - s, mv - s, mu + s, mv + s, COL_MARK)
            }
            "lamp" -> body(if (on) col("on_color", COL_LAMP_ON) else col("off_color", COL_LAMP_OFF))
            "bar" -> {
                body(COL_TRACK)
                val frac = norm(e.value, d("min", 0.0), d("max", 1.0))
                val fill = col("color", COL_FILL)
                if (e.cols >= e.rows) over(u0, v0, u0 + frac * (u1 - u0), v1, fill)
                else over(u0, v1 - frac * (v1 - v0), u1, v1, fill)
            }
            "numeric" -> {
                body(COL_SCREEN)
                val decimals = d("decimals", 1.0).toInt().coerceIn(0, 6)
                val num = formatNum(e.value, decimals) + cfg.getString("suffix")
                val label = cfg.getString("label")
                val text = if (label.isNotEmpty()) "$label $num" else num
                texts.add(TextDraw(u0, v0, u1, v1, text, COL_TEXT))
            }
            "screen" -> {
                body(COL_SCREEN)
                be.videoHandle(e.pinId())?.let { videos.add(VideoDraw(u0, v0, u1, v1, it)) }
            }
            "label" -> {
                body(COL_LABEL)
                val text = cfg.getString("text")
                if (text.isNotEmpty()) texts.add(TextDraw(u0, v0, u1, v1, text, COL_TEXT))
            }
            else -> body(COL_BODY)
        }
    }

    /** Double-sided colour quad over grid rect [u0,v0]–[u1,v1] at [outset]. */
    private fun rect(
        consumer: VertexConsumer,
        m: Matrix4f,
        face: Direction,
        spin: Int,
        u0: Double,
        v0: Double,
        u1: Double,
        v1: Double,
        argb: Int,
        outset: Double,
    ) {
        val tl = PanelSurface.local(u0, v0, face, spin, outset)
        val tr = PanelSurface.local(u1, v0, face, spin, outset)
        val br = PanelSurface.local(u1, v1, face, spin, outset)
        val bl = PanelSurface.local(u0, v1, face, spin, outset)
        val a = (argb ushr 24) and 0xFF
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        fun vert(p: FloatArray) = consumer.addVertex(m, p[0], p[1], p[2]).setUv(0f, 0f).setColor(r, g, b, a)
        vert(tl); vert(bl); vert(br); vert(tr) // front
        vert(tr); vert(br); vert(bl); vert(tl) // back (cull-proof)
    }

    private fun norm(value: Double, min: Double, max: Double): Double {
        if (max == min) return 0.0
        return ((value - min) / (max - min)).coerceIn(0.0, 1.0)
    }

    /**
     * Draw [text] fitted + centred into the grid rect, oriented to the [face]
     * (any face / spin). Builds a basis matrix from the surface axes: text-x →
     * grid-u, text-y → grid-v, text-z → outward normal, then scales to fit.
     */
    private fun drawText(
        m: Matrix4f,
        buffers: MultiBufferSource,
        font: Font,
        face: Direction,
        spin: Int,
        u0: Double,
        v0: Double,
        u1: Double,
        v1: Double,
        text: String,
        argb: Int,
    ) {
        if (text.isEmpty()) return
        val o = PanelSurface.local(u0, v0, face, spin, OUT_TEXT)
        val ue = PanelSurface.local(u1, v0, face, spin, OUT_TEXT)
        val ve = PanelSurface.local(u0, v1, face, spin, OUT_TEXT)
        val ux = floatArrayOf(ue[0] - o[0], ue[1] - o[1], ue[2] - o[2])
        val vy = floatArrayOf(ve[0] - o[0], ve[1] - o[1], ve[2] - o[2])
        val rectW = len3(ux)
        val rectH = len3(vy)
        val w = font.width(text).toFloat()
        if (rectW <= 0f || rectH <= 0f || w <= 0f) return
        val ud = norm3(ux)
        val vd = norm3(vy)
        val lineH = 8f
        val scale = minOf(rectW / w, rectH / lineH) * 0.85f
        val offU = (rectW - w * scale) / 2f
        val offV = (rectH - lineH * scale) / 2f
        val ox = o[0] + ud[0] * offU + vd[0] * offV
        val oy = o[1] + ud[1] * offU + vd[1] * offV
        val oz = o[2] + ud[2] * offU + vd[2] * offV
        val n = normalOf(face)
        // JOML column-major: (col0 | col1 | col2 | col3).
        val basis = Matrix4f(
            ud[0] * scale, ud[1] * scale, ud[2] * scale, 0f,
            vd[0] * scale, vd[1] * scale, vd[2] * scale, 0f,
            n[0], n[1], n[2], 0f,
            ox, oy, oz, 1f,
        )
        val full = Matrix4f(m).mul(basis)
        font.drawInBatch(text, 0f, 0f, argb, false, full, buffers, Font.DisplayMode.NORMAL, 0, LightTexture.FULL_BRIGHT)
    }

    private fun formatNum(value: Double, decimals: Int): String =
        String.format(java.util.Locale.ROOT, "%.${decimals}f", value)

    private fun len3(v: FloatArray): Float = kotlin.math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])

    private fun norm3(v: FloatArray): FloatArray {
        val l = len3(v)
        return if (l <= 0f) v else floatArrayOf(v[0] / l, v[1] / l, v[2] / l)
    }

    private fun normalOf(face: Direction): FloatArray = when (face) {
        Direction.UP -> floatArrayOf(0f, 1f, 0f)
        Direction.DOWN -> floatArrayOf(0f, -1f, 0f)
        Direction.NORTH -> floatArrayOf(0f, 0f, -1f)
        Direction.SOUTH -> floatArrayOf(0f, 0f, 1f)
        Direction.EAST -> floatArrayOf(1f, 0f, 0f)
        Direction.WEST -> floatArrayOf(-1f, 0f, 0f)
    }

    /**
     * Placement guide — the 16×16 grid plus a footprint ghost — shown only on the
     * panel the local player points at while holding an element item / the Panel
     * Key. Drawn in THIS pass at a higher outset than the elements, so it sits
     * cleanly in front of the surface and never z-fights (no separate line pass).
     * The ghost uses the same [ELEMENT_GAP] inset as the elements, so its size
     * matches exactly: green = fits, red = blocked; orange outlines the Panel Key's
     * removal target.
     */
    private fun drawGuide(consumer: VertexConsumer, m: Matrix4f, be: ControlPanelBlockEntity, face: Direction, spin: Int) {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val held = player.mainHandItem.item
        val element = held as? PanelElementItem
        val isLinkTool = held is ChannelLinkToolItem
        if (element == null && held !is PanelKeyItem && !isLinkTool) return
        val hr = mc.hitResult as? BlockHitResult ?: return
        if (hr.blockPos != be.blockPos || hr.direction != face) return

        for (i in 0..16) {
            val t = i / 16.0
            guideLine(consumer, m, face, spin, t, 0.0, t, 1.0)
            guideLine(consumer, m, face, spin, 0.0, t, 1.0, t)
        }

        val hit = ControlPanelBlock.gridHit(be.blockState, be.blockPos, hr.location) ?: return
        if (element != null) {
            val type = PanelElements.byId(element.typeId) ?: return
            val anchor = ControlPanelBlock.clampAnchor(hit.cell, type.cols, type.rows)
            val blocked = PanelGrid.overlaps(be.occupiedCells(), anchor, type.cols, type.rows)
            ghostOutline(consumer, m, face, spin, anchor.x, anchor.y, type.cols, type.rows, if (blocked) COL_BLOCKED else COL_FREE)
        } else {
            // Panel Key: orange = removal/config target. Link Tool: cyan = the
            // element whose pin arms/commits on click (mirrors LinkHud's
            // pointing-wins highlight).
            be.elementAt(hit.cell)?.let {
                val color = if (isLinkTool) COL_LINK else COL_KEY
                ghostOutline(consumer, m, face, spin, it.cellX, it.cellY, it.cols, it.rows, color)
            }
        }
    }

    private fun guideLine(consumer: VertexConsumer, m: Matrix4f, face: Direction, spin: Int, u0: Double, v0: Double, u1: Double, v1: Double) {
        val w = GRID_HW
        if (u0 == u1) rect(consumer, m, face, spin, u0 - w, v0, u0 + w, v1, COL_GRID, OUT_GRID)
        else rect(consumer, m, face, spin, u0, v0 - w, u1, v0 + w, COL_GRID, OUT_GRID)
    }

    private fun ghostOutline(consumer: VertexConsumer, m: Matrix4f, face: Direction, spin: Int, cx: Int, cy: Int, cols: Int, rows: Int, color: Int) {
        val g = ELEMENT_GAP
        val u0 = (cx + g) / 16.0; val v0 = (cy + g) / 16.0
        val u1 = (cx + cols - g) / 16.0; val v1 = (cy + rows - g) / 16.0
        val w = GHOST_HW
        rect(consumer, m, face, spin, u0, v0 - w, u1, v0 + w, color, OUT_GHOST) // top
        rect(consumer, m, face, spin, u0, v1 - w, u1, v1 + w, color, OUT_GHOST) // bottom
        rect(consumer, m, face, spin, u0 - w, v0, u0 + w, v1, color, OUT_GHOST) // left
        rect(consumer, m, face, spin, u1 - w, v0, u1 + w, v1, color, OUT_GHOST) // right
    }

    /** A deferred text draw (collected during the quad pass, drawn after it). */
    private class TextDraw(
        val u0: Double,
        val v0: Double,
        val u1: Double,
        val v1: Double,
        val text: String,
        val color: Int,
    )

    /** A deferred mini-screen video blit (drawn after the quad pass). */
    private class VideoDraw(
        val u0: Double,
        val v0: Double,
        val u1: Double,
        val v1: Double,
        val handle: java.util.UUID,
    )

    /**
     * Blit a video handle's FBO into the element rect. UVs put texture v=0 at
     * the rect's BOTTOM edge (grid v1) — FBO colour attachments are bottom-up,
     * same convention as ScreenBlockRenderer.emitFace. Double-sided.
     */
    private fun drawVideo(m: Matrix4f, buffers: MultiBufferSource, face: Direction, spin: Int, v: VideoDraw) {
        val surface = dev.nitka.nodewire.client.video.VideoManager.getOrCreate(v.handle)
            as? dev.nitka.nodewire.client.video.GlVideoSurface ?: return
        val consumer = buffers.getBuffer(VideoBlit.plainTypeFor(surface.colorTextureId()))
        val tl = PanelSurface.local(v.u0, v.v0, face, spin, OUT_OVER)
        val tr = PanelSurface.local(v.u1, v.v0, face, spin, OUT_OVER)
        val br = PanelSurface.local(v.u1, v.v1, face, spin, OUT_OVER)
        val bl = PanelSurface.local(v.u0, v.v1, face, spin, OUT_OVER)
        fun vert(p: FloatArray, u: Float, vv: Float) =
            consumer.addVertex(m, p[0], p[1], p[2]).setUv(u, vv).setColor(1f, 1f, 1f, 1f)
        vert(tl, 0f, 1f); vert(bl, 0f, 0f); vert(br, 1f, 0f); vert(tr, 1f, 1f) // front
        vert(tr, 1f, 1f); vert(br, 1f, 0f); vert(bl, 0f, 0f); vert(tl, 0f, 1f) // back
    }

    companion object {
        private const val ELEMENT_GAP = 0.06 // cell inset between an element body and its footprint
        private const val OUT_PLATE = 0.010
        private const val OUT_BODY = 0.015
        private const val OUT_OVER = 0.020
        private const val OUT_GRID = 0.025
        private const val OUT_GHOST = 0.029
        private const val OUT_TEXT = 0.022
        private const val GRID_HW = 0.0016 // grid-line half-width in grid (u,v) units
        private const val GHOST_HW = 0.004

        private val COL_TEXT = 0xFFE8F0F0.toInt()
        private val COL_GRID = 0xFF9AA0A6.toInt()
        private val COL_FREE = 0xFF3FD24A.toInt()
        private val COL_BLOCKED = 0xFFE0403A.toInt()
        private val COL_KEY = 0xFFE8A23A.toInt()
        private val COL_LINK = 0xFF5CC8E8.toInt()

        private const val COL_PLATE = 0xFF202225.toInt()
        private const val COL_BODY = 0xFF334455.toInt()
        private const val COL_ON = 0xFF33CC44.toInt()
        private const val COL_OFF = 0xFF553333.toInt()
        private const val COL_BTN = 0xFF445588.toInt()
        private const val COL_PRESS = 0xFF88AAFF.toInt()
        private const val COL_MARK = 0xFFFFCC33.toInt()
        private const val COL_TRACK = 0xFF1A1A1A.toInt()
        private const val COL_THUMB = 0xFFCCCCCC.toInt()
        private const val COL_LAMP_ON = 0xFFFF3333.toInt()
        private const val COL_LAMP_OFF = 0xFF331111.toInt()
        private const val COL_FILL = 0xFF33CCCC.toInt()
        private const val COL_SCREEN = 0xFF050505.toInt()
        private const val COL_LABEL = 0xFF2E2E2E.toInt()

        private var whiteTex: DynamicTexture? = null

        private fun whiteTexId(): Int {
            var t = whiteTex
            if (t == null) {
                val img = NativeImage(1, 1, false)
                img.setPixelRGBA(0, 0, -1) // 0xFFFFFFFF
                t = DynamicTexture(img)
                whiteTex = t
            }
            return t.id
        }
    }
}
