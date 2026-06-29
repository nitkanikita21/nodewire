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
import dev.nitka.nodewire.item.PanelElementItem
import dev.nitka.nodewire.item.PanelKeyItem
import net.minecraft.client.Minecraft
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
        val elements = be.elements()
        val face = be.blockState.getValue(ControlPanelBlock.FACE)
        val spin = be.blockState.getValue(ControlPanelBlock.SPIN)

        poseStack.pushPose()
        val matrix = poseStack.last().pose()
        val consumer = buffers.getBuffer(VideoBlit.plainTypeFor(whiteTexId()))

        rect(consumer, matrix, face, spin, 0.0, 0.0, 1.0, 1.0, COL_PLATE, OUT_PLATE)
        for (e in elements) drawElement(consumer, matrix, face, spin, e)
        drawGuide(consumer, matrix, be, face, spin)
        poseStack.popPose()
    }

    override fun getRenderBoundingBox(be: ControlPanelBlockEntity): net.minecraft.world.phys.AABB =
        net.minecraft.world.phys.AABB(be.blockPos)

    private fun drawElement(consumer: VertexConsumer, m: Matrix4f, face: Direction, spin: Int, e: PlacedElement) {
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
                val frac = norm(e.value, d("min", 0.0), d("max", 1.0))
                over(u0, v1 - (v1 - v0) * 0.18, u0 + frac * (u1 - u0), v1, COL_FILL)
            }
            "screen" -> body(COL_SCREEN)
            "label" -> body(COL_LABEL)
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
        if (element == null && held !is PanelKeyItem) return
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
            be.elementAt(hit.cell)?.let {
                ghostOutline(consumer, m, face, spin, it.cellX, it.cellY, it.cols, it.rows, COL_KEY)
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

    companion object {
        private const val ELEMENT_GAP = 0.06 // cell inset between an element body and its footprint
        private const val OUT_PLATE = 0.010
        private const val OUT_BODY = 0.015
        private const val OUT_OVER = 0.020
        private const val OUT_GRID = 0.025
        private const val OUT_GHOST = 0.029
        private const val GRID_HW = 0.0016 // grid-line half-width in grid (u,v) units
        private const val GHOST_HW = 0.004

        private val COL_GRID = 0xFF9AA0A6.toInt()
        private val COL_FREE = 0xFF3FD24A.toInt()
        private val COL_BLOCKED = 0xFFE0403A.toInt()
        private val COL_KEY = 0xFFE8A23A.toInt()

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
