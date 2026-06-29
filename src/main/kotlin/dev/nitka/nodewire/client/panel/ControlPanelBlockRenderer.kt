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
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.core.Direction
import org.joml.Matrix4f
import kotlin.math.cos
import kotlin.math.sin

/**
 * First-pass procedural renderer for the [ControlPanelBlock]: draws the plate
 * background and every placed element as flat colour quads on the panel face,
 * with the live operated/driven value shown as a state overlay (toggle colour,
 * slider thumb, bar fill, lamp glow, …).
 *
 * Geometry is the exact inverse of [PanelGrid.hitToGrid] — grid `(u,v)` (top-left
 * origin, u right, v down) is un-spun and mapped back onto the [ControlPanelBlock.FACE]
 * plane — so an element renders precisely where you click to hit its cell. Quads
 * are double-sided (emitted both windings) to dodge back-face culling, and drawn
 * full-bright (the POSITION_TEX_COLOR path applies no lightmap) for a HUD look.
 *
 * Reuses [VideoBlit.plainTypeFor] with a 1×1 white texture: white × vertex colour
 * = a solid colour quad, so no new render pipeline is needed. Baked element bodies
 * + textures + value text are a later slice.
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

        // Plate background.
        rect(consumer, matrix, face, spin, 0.0, 0.0, 1.0, 1.0, COL_PLATE)

        for (e in elements) drawElement(consumer, matrix, face, spin, e)
        poseStack.popPose()
    }

    override fun getRenderBoundingBox(be: ControlPanelBlockEntity): net.minecraft.world.phys.AABB =
        net.minecraft.world.phys.AABB(be.blockPos)

    private fun drawElement(consumer: VertexConsumer, m: Matrix4f, face: Direction, spin: Int, e: PlacedElement) {
        if (PanelElements.byId(e.typeId) == null) return
        // Element bounds in grid space (with a small inset gap between neighbours).
        val gap = 0.06
        val u0 = (e.cellX + gap) / 16.0
        val v0 = (e.cellY + gap) / 16.0
        val u1 = (e.cellX + e.cols - gap) / 16.0
        val v1 = (e.cellY + e.rows - gap) / 16.0
        val cfg = e.config
        fun d(key: String, dflt: Double) = if (cfg.contains(key)) cfg.getDouble(key) else dflt
        fun col(key: String, dflt: Int) = if (cfg.contains(key)) cfg.getInt(key) else dflt
        val on = e.value != 0.0

        when (e.typeId) {
            "toggle" -> {
                rect(consumer, m, face, spin, u0, v0, u1, v1, if (on) COL_ON else COL_OFF)
            }
            "momentary" -> {
                rect(consumer, m, face, spin, u0, v0, u1, v1, if (on) COL_PRESS else COL_BTN)
            }
            "selector" -> {
                rect(consumer, m, face, spin, u0, v0, u1, v1, COL_BODY)
                val positions = (d("positions", 2.0)).toInt().coerceAtLeast(1)
                val idx = e.value.toInt().coerceIn(0, positions - 1)
                // marker band across the top, segmented by position.
                val segW = (u1 - u0) / positions
                val mu0 = u0 + idx * segW
                rect(consumer, m, face, spin, mu0, v0, mu0 + segW, v0 + (v1 - v0) * 0.25, COL_MARK)
            }
            "slider" -> {
                rect(consumer, m, face, spin, u0, v0, u1, v1, COL_TRACK)
                val frac = norm(e.value, d("min", 0.0), d("max", 1.0))
                if (e.cols >= e.rows) {
                    val tx = u0 + frac * (u1 - u0)
                    val tw = (u1 - u0) * 0.12
                    rect(consumer, m, face, spin, (tx - tw / 2).coerceIn(u0, u1 - tw), v0, (tx + tw / 2).coerceIn(u0 + tw, u1), v1, COL_THUMB)
                } else {
                    val ty = v1 - frac * (v1 - v0)
                    val th = (v1 - v0) * 0.12
                    rect(consumer, m, face, spin, u0, (ty - th / 2).coerceIn(v0, v1 - th), u1, (ty + th / 2).coerceIn(v0 + th, v1), COL_THUMB)
                }
            }
            "knob" -> {
                rect(consumer, m, face, spin, u0, v0, u1, v1, COL_BODY)
                val frac = norm(e.value, d("min", 0.0), d("max", 1.0))
                val sweep = d("sweep", 270.0)
                val ang = Math.toRadians(frac * sweep)
                val cu = (u0 + u1) / 2; val cv = (v0 + v1) / 2
                val r = minOf(u1 - u0, v1 - v0) * 0.35
                val mu = cu + sin(ang) * r; val mv = cv + cos(ang) * r // 0° = straight down (+v)
                val s = (u1 - u0) * 0.10
                rect(consumer, m, face, spin, mu - s, mv - s, mu + s, mv + s, COL_MARK)
            }
            "lamp" -> {
                rect(consumer, m, face, spin, u0, v0, u1, v1, if (on) col("on_color", COL_LAMP_ON) else col("off_color", COL_LAMP_OFF))
            }
            "bar" -> {
                rect(consumer, m, face, spin, u0, v0, u1, v1, COL_TRACK)
                val frac = norm(e.value, d("min", 0.0), d("max", 1.0))
                val fill = col("color", COL_FILL)
                if (e.cols >= e.rows) {
                    rect(consumer, m, face, spin, u0, v0, u0 + frac * (u1 - u0), v1, fill)
                } else {
                    rect(consumer, m, face, spin, u0, v1 - frac * (v1 - v0), u1, v1, fill)
                }
            }
            "numeric" -> {
                rect(consumer, m, face, spin, u0, v0, u1, v1, COL_SCREEN)
                // value as a fill bar — text rendering is a later slice.
                val frac = norm(e.value, d("min", 0.0), d("max", 1.0))
                rect(consumer, m, face, spin, u0, v1 - (v1 - v0) * 0.18, u0 + frac * (u1 - u0), v1, COL_FILL)
            }
            "screen" -> rect(consumer, m, face, spin, u0, v0, u1, v1, COL_SCREEN)
            "label" -> rect(consumer, m, face, spin, u0, v0, u1, v1, COL_LABEL)
            else -> rect(consumer, m, face, spin, u0, v0, u1, v1, COL_BODY)
        }
    }

    /** Map grid `(u,v)` → block-local 3D on [face] with [spin], outset off the plane. */
    private fun gridToLocal(u: Double, v: Double, face: Direction, spin: Int): FloatArray {
        // Inverse spin (rotate back), then face-uv → local.
        val (su, sv) = PanelGrid.applySpin(u, v, (4 - (spin % 4)) % 4)
        val o = OUTSET
        return when (face) {
            Direction.SOUTH -> floatArrayOf(su.toFloat(), (1 - sv).toFloat(), (1 + o).toFloat())
            Direction.NORTH -> floatArrayOf((1 - su).toFloat(), (1 - sv).toFloat(), (-o).toFloat())
            Direction.EAST -> floatArrayOf((1 + o).toFloat(), (1 - sv).toFloat(), (1 - su).toFloat())
            Direction.WEST -> floatArrayOf((-o).toFloat(), (1 - sv).toFloat(), su.toFloat())
            Direction.UP -> floatArrayOf(su.toFloat(), (1 + o).toFloat(), sv.toFloat())
            Direction.DOWN -> floatArrayOf(su.toFloat(), (-o).toFloat(), (1 - sv).toFloat())
        }
    }

    /** Double-sided colour quad over the grid rect [u0,v0]–[u1,v1]. */
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
    ) {
        val tl = gridToLocal(u0, v0, face, spin)
        val tr = gridToLocal(u1, v0, face, spin)
        val br = gridToLocal(u1, v1, face, spin)
        val bl = gridToLocal(u0, v1, face, spin)
        val a = (argb ushr 24) and 0xFF
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        fun vert(p: FloatArray) = consumer.addVertex(m, p[0], p[1], p[2]).setUv(0f, 0f).setColor(r, g, b, a)
        // front winding
        vert(tl); vert(bl); vert(br); vert(tr)
        // back winding (so it shows regardless of which side culling keeps)
        vert(tr); vert(br); vert(bl); vert(tl)
    }

    private fun norm(value: Double, min: Double, max: Double): Double {
        if (max == min) return 0.0
        return ((value - min) / (max - min)).coerceIn(0.0, 1.0)
    }

    companion object {
        private const val OUTSET = 0.012

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

        /** 1×1 white texture id — tinted by vertex colour to draw flat colour quads. */
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
