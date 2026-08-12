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
 * Fully-3D procedural renderer for the [ControlPanelBlock] — the Power-Grid
 * look, but generated at draw time from prisms instead of baked models. Every
 * element is a raised housing with shaded side walls; moving parts transform
 * with the live value:
 *
 *  * toggle — a lever WEDGE that flips up (on) / down (off);
 *  * momentary — a cap that presses IN while active;
 *  * selector — a rotary pointer stepping across the sweep;
 *  * slider — a raised thumb riding the recessed track;
 *  * knob — a rotating cap with a notch;
 *  * lamp — a tinted dome (bright when lit);
 *  * bar / numeric — an LCD housing with a recessed window carrying a glowing
 *    fill / the value text;
 *  * screen — a protruding bezel whose front face blits the video feed;
 *  * label — a plate that SCALES to its text.
 *
 * Geometry is the exact inverse of [PanelGrid.hitToGrid] via [PanelSurface]
 * (top-left origin, u right, v down; heights = outsets off the mounting wall),
 * so an element renders precisely where you click it. Faces fake lighting by
 * shading walls (top light / bottom dark). Buffer discipline: ALL colour quads
 * go into one held buffer; video blits and text are collected and drawn LAST
 * (requesting another render type mid-stream would end the quad buffer —
 * "Not building!"). The whole BER skips camera-capture passes.
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
        if (VideoManager.isCapturing()) return

        val elements = be.elements()
        val face = be.blockState.getValue(ControlPanelBlock.FACE)
        val spin = be.blockState.getValue(ControlPanelBlock.SPIN)

        poseStack.pushPose()
        val m = poseStack.last().pose()
        val font = Minecraft.getInstance().font
        val consumer = buffers.getBuffer(VideoBlit.plainTypeFor(whiteTexId()))
        val texts = ArrayList<TextDraw>()
        val videos = ArrayList<VideoDraw>()

        for (e in elements) drawElement(consumer, m, be, font, face, spin, e, texts, videos)
        drawGuide(consumer, m, be, face, spin)
        // Baked pass (Dashpanels pipeline port) AFTER the colour-quad pass —
        // model/text buffers switch the active buffer, ending the quad one.
        pushSurfaceBasis(poseStack, face, spin)
        PanelModel.PLATE.render(poseStack, buffers, net.minecraft.client.renderer.RenderType.cutout(), light)
        for (e in elements) {
            if (e.typeId in BAKED_TYPES) drawBaked(poseStack, buffers, font, be, e, light)
        }
        poseStack.popPose()
        for (v in videos) drawVideo(m, buffers, face, spin, v)
        for (t in texts) drawText(m, buffers, font, face, spin, t)
        poseStack.popPose()
    }

    override fun getRenderBoundingBox(be: ControlPanelBlockEntity): net.minecraft.world.phys.AABB =
        net.minecraft.world.phys.AABB(be.blockPos)

    // ── elements ──────────────────────────────────────────────────────────

    private fun drawElement(
        consumer: VertexConsumer,
        m: Matrix4f,
        be: ControlPanelBlockEntity,
        font: Font,
        face: Direction,
        spin: Int,
        e: PlacedElement,
        texts: MutableList<TextDraw>,
        videos: MutableList<VideoDraw>,
    ) {
        if (PanelElements.byId(e.typeId) == null) return
        if (e.typeId in BAKED_TYPES) return // Dashpanels-model pass below
        val gap = ELEMENT_GAP
        val u0 = (e.cellX + gap) / 16.0
        val v0 = (e.cellY + gap) / 16.0
        val u1 = (e.cellX + e.cols - gap) / 16.0
        val v1 = (e.cellY + e.rows - gap) / 16.0
        val cu = (u0 + u1) / 2
        val cv = (v0 + v1) / 2
        val w = u1 - u0
        val h = v1 - v0
        val cfg = e.config
        fun d(key: String, dflt: Double) = if (cfg.contains(key)) cfg.getDouble(key) else dflt
        fun col(key: String, dflt: Int) = if (cfg.contains(key)) cfg.getInt(key) else dflt
        val on = e.value != 0.0

        when (e.typeId) {
            in SCREEN_IDS -> {
                // Protruding bezel; the feed blits on its front face — only
                // while powered (`enable` pin; e.value doubles as the flag).
                box(consumer, m, face, spin, u0, v0, u1, v1, OUT_PLATE, H_BASE + 0.004, COL_BODY)
                // Bezel width in grid units (constant, not footprint-relative,
                // so big screens don't get comically thick frames).
                val bw = minOf(w, h) * 0.06 + 0.002
                rect(consumer, m, face, spin, u0 + bw, v0 + bw, u1 - bw, v1 - bw, COL_SCREEN, H_BASE + 0.006)
                if (on) {
                    be.videoHandle(e.pinId())?.let {
                        videos.add(VideoDraw(u0 + bw, v0 + bw, u1 - bw, v1 - bw, it, H_BASE + 0.008))
                    }
                } else {
                    // Standby dot in the window corner so an off screen reads
                    // as powered-down, not broken.
                    val d = minOf(w, h) * 0.04
                    rect(consumer, m, face, spin, u1 - bw - 2 * d, v1 - bw - 2 * d, u1 - bw - d, v1 - bw - d, COL_OFF, H_BASE + 0.008)
                }
            }
            else -> box(consumer, m, face, spin, u0, v0, u1, v1, OUT_PLATE, H_BASE, COL_BODY)
        }
    }

    // ── Dashpanels-pipeline baked pass ────────────────────────────────────
    // A 1:1 port of Dashpanels' AbstractPanelRenderer module frame + the
    // individual module renders (MIT, BoxxedDev): per module, translate to its
    // cell anchor, rotate 180° about the FOOTPRINT CENTRE (their models are
    // authored flipped), then replay the module's own part transforms.

    /** Push a unit basis mapping model X→grid-u, Y→outward normal, Z→grid-v
     *  with the origin at grid (0,0) on the plate plane — our stand-in for
     *  their facing-rotation + `renderTransform` (natural scale: 1 model
     *  sixteenth = 1 grid cell). */
    private fun pushSurfaceBasis(pose: PoseStack, face: Direction, spin: Int) {
        val o = PanelSurface.local(0.0, 0.0, face, spin, OUT_PLATE)
        val ue = PanelSurface.local(0.5, 0.0, face, spin, OUT_PLATE)
        val ve = PanelSurface.local(0.0, 0.5, face, spin, OUT_PLATE)
        val ud = norm3(floatArrayOf(ue[0] - o[0], ue[1] - o[1], ue[2] - o[2]))
        val vd = norm3(floatArrayOf(ve[0] - o[0], ve[1] - o[1], ve[2] - o[2]))
        val n = normalOf(face)
        pose.pushPose()
        pose.mulPose(
            Matrix4f(
                ud[0], ud[1], ud[2], 0f,
                n[0], n[1], n[2], 0f,
                vd[0], vd[1], vd[2], 0f,
                o[0], o[1], o[2], 1f,
            ),
        )
    }

    private fun drawBaked(pose: PoseStack, buffers: MultiBufferSource, font: Font, be: ControlPanelBlockEntity, e: PlacedElement, light: Int) {
        val on = e.value != 0.0
        val cfg = e.config
        fun d(key: String, dflt: Double) = if (cfg.contains(key)) cfg.getDouble(key) else dflt
        fun col(key: String, dflt: Int) = if (cfg.contains(key)) cfg.getInt(key) else dflt
        val solid = net.minecraft.client.renderer.RenderType.solid()
        val cutout = net.minecraft.client.renderer.RenderType.cutout()
        val translucent = net.minecraft.client.renderer.RenderType.translucent()

        pose.pushPose()
        // Dashpanels' individualModuleTransform: edge epsilon keeps edge-column
        // quads off the panel border; then the module frame (180° about centre).
        val eps = when {
            e.cellX == 0 -> 0.0001f
            e.cellX + e.cols == 16 -> -0.0001f
            else -> 0f
        }
        pose.translate(e.cellX / 16f + eps, 0f, e.cellY / 16f)
        pose.rotateAround(com.mojang.math.Axis.YP.rotationDegrees(180f), e.cols / 32f, 0f, e.rows / 32f)

        val animKey = "${e.hashCode()}:${e.pinId()}"
        when (e.typeId) {
            "switch" -> (if (on) PanelModel.SWITCH_ON else PanelModel.SWITCH_OFF).render(pose, buffers, solid, light)
            "momentary" -> {
                PanelModel.MOMENTARY_BASE.render(pose, buffers, solid, light)
                val y = PanelAnim.approach(animKey, if (on) -0.5f / 16f + 0.001f else 0f, 0.5f)
                pose.pushPose()
                pose.translate(0f, y, 0f)
                PanelModel.MOMENTARY_BUTTON.render(pose, buffers, solid, light)
                pose.popPose()
            }
            "push_button" -> {
                val buttons = (if (cfg.contains("buttons")) cfg.getInt("buttons") else 1).coerceIn(1, 8)
                val gap = (if (cfg.contains("gap")) cfg.getInt("gap") else 0).coerceIn(0, 2)
                val selected = e.value.toInt() - 1 // -1 = none
                for (i in 1..buttons) {
                    pose.pushPose()
                    pose.translate((buttons - i) * 2 / 16f + (buttons - i) * gap / 16f, 0f, 0f)
                    PanelModel.PUSH_BUTTON_BASE.render(pose, buffers, cutout, light)
                    val sel = (i - 1) == selected
                    pose.translate(0f, if (sel) -0.25f / 16f else 0f, 0f)
                    (if (sel) PanelModel.PUSH_BUTTON_LIT else PanelModel.PUSH_BUTTON).render(pose, buffers, cutout, light)
                    pose.popPose()
                }
            }
            "key_switch" -> {
                PanelModel.KEY_SWITCH_BASE.render(pose, buffers, cutout, light)
                val turn = PanelAnim.approach(animKey, if (on) -90f else 0f, 0.5f)
                pose.pushPose()
                pose.rotateAround(com.mojang.math.Axis.YP.rotationDegrees(turn), 1 / 16f, 0f, 1 / 16f)
                PanelModel.KEY_SWITCH_HOLE.render(pose, buffers, cutout, light)
                PanelModel.KEY_SWITCH_KEY.render(pose, buffers, cutout, light)
                pose.popPose()
            }
            "emergency" -> {
                val bits = e.value.toInt()
                val open = bits and 2 != 0
                val pressed = bits and 1 != 0
                PanelModel.EMERGENCY_BASE.render(pose, buffers, cutout, light)
                val openTime = PanelAnim.linear(animKey, if (open) 1f else 0f, 0.1f)
                pose.pushPose()
                pose.rotateAround(com.mojang.math.Axis.XP.rotationDegrees(easeOutBack(openTime) * 75f), 0f, 0f, 0.25f)
                PanelModel.EMERGENCY_COVER.render(pose, buffers, translucent, light)
                pose.popPose()
                pose.pushPose()
                if (pressed) pose.translate(0.0, -0.75 / 16.0, 0.0)
                PanelModel.EMERGENCY_BUTTON.render(pose, buffers, cutout, light)
                pose.popPose()
            }
            "lever" -> {
                PanelModel.LEVER_BASE.render(pose, buffers, solid, light)
                val target = (e.value.coerceIn(0.0, 15.0) / 15.0 * 0.25).toFloat()
                val handleZ = PanelAnim.approach("$animKey:h", target, 0.5f)
                val indicatorZ = PanelAnim.approach("$animKey:i", target, 0.15f)
                pose.pushPose()
                pose.translate(0f, 0f, handleZ)
                PanelModel.LEVER_HANDLE.render(pose, buffers, solid, light)
                pose.popPose()
                pose.pushPose()
                pose.translate(0f, 0f, indicatorZ)
                PanelModel.LEVER_INDICATOR.render(pose, buffers, solid, light)
                pose.popPose()
            }
            "knob" -> {
                val angle = PanelAnim.approach(animKey, (e.value.coerceIn(0.0, 1.0) * 360.0).toFloat(), 0.75f)
                pose.pushPose()
                pose.rotateAround(com.mojang.math.Axis.YP.rotationDegrees(angle - 45f), 1 / 16f, 0f, 1 / 16f)
                PanelModel.KNOB.render(pose, buffers, solid, light)
                pose.popPose()
            }
            "joystick" -> {
                val joy = be.joyState(e.pinId())
                val rsx = PanelAnim.approach("$animKey:x", joy?.get(0) ?: 0f, 0.5f)
                val rsy = PanelAnim.approach("$animKey:y", joy?.get(1) ?: 0f, 0.5f)
                val angleX = rsx * 7.5f
                val angleY = rsy * 7.5f
                PanelModel.JOYSTICK_BASE.render(pose, buffers, solid, light)
                pose.pushPose()
                pose.translate(0.125, 0.03125, 0.125)
                pose.mulPose(com.mojang.math.Axis.XP.rotationDegrees(angleY))
                pose.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(angleX))
                PanelModel.JOYSTICK_BETWEEN.render(pose, buffers, solid, light)
                pose.pushPose()
                pose.translate(0.0, 0.03125, 0.0)
                pose.mulPose(com.mojang.math.Axis.XP.rotationDegrees(angleY))
                pose.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(angleX))
                PanelModel.JOYSTICK_STICK.render(pose, buffers, solid, light)
                pose.pushPose()
                pose.translate(0.0, 0.125, 0.0)
                pose.mulPose(com.mojang.math.Axis.XP.rotationDegrees(22.5f))
                PanelModel.JOYSTICK_TRIGGER.render(pose, buffers, solid, light)
                pose.popPose()
                pose.popPose()
                pose.popPose()
            }
            "bulb" -> {
                pose.pushPose()
                pose.translate(0.0, 0.0, 0.5 / 16.0)
                PanelModel.BULB_BASE.render(pose, buffers, solid, light)
                val tint = col("on_color", COL_LAMP_ON) and 0xFFFFFF
                val bulb = if (on) PanelModel.BULB_ON else PanelModel.BULB_OFF
                bulb.render(pose, buffers, translucent, if (on) LightTexture.FULL_BRIGHT else light, tint)
                pose.popPose()
            }
            "seven_segment" -> {
                pose.pushPose()
                pose.translate(0f, -1 / 32f, 0f)
                PanelModel.SEVEN_SEGMENT.render(pose, buffers, solid, light)
                pose.popPose()
                val decimals = d("decimals", 0.0).toInt().coerceIn(0, 6)
                val num = formatNum(e.value, decimals) + cfg.getString("suffix")
                moduleText(pose, buffers, font, num, col("color", 0xFFFFFFFF.toInt()), e.cols, e.rows, 1 / 32f, fullBright = true)
            }
            "buzzer" -> PanelModel.BUZZER.render(pose, buffers, cutout, light)
            "label" -> {
                pose.pushPose()
                pose.translate(0f, 0.001f, 0f)
                PanelModel.LABEL.render(pose, buffers, cutout, light)
                pose.popPose()
                moduleText(pose, buffers, font, cfg.getString("text"), 0xFF2A2D31.toInt(), e.cols, e.rows, 0.003f, capHCells = 1f)
            }
        }
        pose.popPose()
    }

    /** Dashpanels' emergency-cover ease (back-out). */
    private fun easeOutBack(x: Float): Float {
        val c1 = 1.70158f
        val c3 = c1 + 1f
        val t = x - 1f
        return 1f + c3 * t * t * t + c1 * t * t
    }

    /**
     * Client-side animation smoothing standing in for Dashpanels' BE-tick
     * lerps: exponential approach with the SAME per-tick factor, made
     * frame-rate independent via per-key timestamps.
     */
    private object PanelAnim {
        private class Cell(var value: Float, var nanos: Long)

        private val cells = HashMap<String, Cell>()

        fun approach(key: String, target: Float, perTick: Float): Float {
            val now = System.nanoTime()
            val cell = cells.getOrPut(key) { Cell(target, now) }
            val dtTicks = ((now - cell.nanos) / 1e9 * 20.0).coerceIn(0.0, 5.0)
            cell.nanos = now
            val f = 1.0 - Math.pow((1.0 - perTick).toDouble(), dtTicks)
            cell.value += ((target - cell.value) * f).toFloat()
            return cell.value
        }

        /** Linear ramp at [ratePerTick] toward [target] (the emergency cover). */
        fun linear(key: String, target: Float, ratePerTick: Float): Float {
            val now = System.nanoTime()
            val cell = cells.getOrPut(key) { Cell(target, now) }
            val dtTicks = ((now - cell.nanos) / 1e9 * 20.0).coerceIn(0.0, 5.0).toFloat()
            cell.nanos = now
            val step = ratePerTick * dtTicks
            cell.value = if (target > cell.value) minOf(cell.value + step, target) else maxOf(cell.value - step, target)
            return cell.value
        }
    }

    /**
     * Text laid flat on a module's face — Dashpanels' seven-segment recipe
     * (lay flat with X+90°, then Z+180° so it reads correctly through the
     * module frame's 180° flip), generalised: centred on the footprint and
     * scaled down to fit it.
     */
    private fun moduleText(
        pose: PoseStack,
        buffers: MultiBufferSource,
        font: Font,
        text: String,
        argb: Int,
        cols: Int,
        rows: Int,
        yLift: Float,
        fullBright: Boolean = false,
        capHCells: Float? = null,
    ) {
        if (text.isEmpty()) return
        val w = font.width(text).toFloat()
        // Font units at scale 1/32: one grid cell = 2 units. [capHCells] caps
        // the text height in cells (a short label shouldn't fill its footprint).
        val maxW = cols * 2f - 1f
        val maxH = capHCells?.let { it * 2f } ?: (rows * 2f - 0.5f)
        val fit = minOf(1f, maxW / w, maxH / LINE_H)
        pose.pushPose()
        pose.translate(cols / 32f, yLift, rows / 32f) // footprint centre
        pose.scale(fit / 32f, fit / 32f, fit / 32f)
        pose.mulPose(com.mojang.math.Axis.XP.rotationDegrees(90f))
        pose.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(180f))
        font.drawInBatch(
            text, -w / 2f, -LINE_H / 2f, argb, false,
            pose.last().pose(), buffers, Font.DisplayMode.POLYGON_OFFSET, 0,
            if (fullBright) LightTexture.FULL_BRIGHT else LightTexture.FULL_BRIGHT,
        )
        pose.popPose()
    }

    // ── 3D primitives (grid space → oriented world quads) ─────────────────

    /** Flat colour rect at [outset] (windows, fills, plate, guide). */
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
        quad(consumer, m, tl, bl, br, tr, argb)
    }

    /** Axis-aligned raised box over the grid rect, from [hBot] to [hTop]. */
    private fun box(
        consumer: VertexConsumer,
        m: Matrix4f,
        face: Direction,
        spin: Int,
        u0: Double,
        v0: Double,
        u1: Double,
        v1: Double,
        hBot: Double,
        hTop: Double,
        argb: Int,
    ) = prism(
        consumer, m, face, spin,
        arrayOf(
            doubleArrayOf(u0, v0), doubleArrayOf(u1, v0),
            doubleArrayOf(u1, v1), doubleArrayOf(u0, v1),
        ),
        hBot, doubleArrayOf(hTop, hTop, hTop, hTop), argb,
    )

    /** Raised box rotated by [angle] about ([cu],[cv]) — knob caps, pointers. */
    private fun rotBox(
        consumer: VertexConsumer,
        m: Matrix4f,
        face: Direction,
        spin: Int,
        cu: Double,
        cv: Double,
        halfW: Double,
        halfH: Double,
        angle: Double,
        hBot: Double,
        hTop: Double,
        argb: Int,
    ) {
        val ca = cos(angle); val sa = sin(angle)
        // Rotation that maps "down" (0,+v) onto (sin a, cos a) — matches the
        // knob/selector value math (angle measured from straight-down).
        fun rot(x: Double, y: Double) = doubleArrayOf(cu + x * ca + y * sa, cv - x * sa + y * ca)
        prism(
            consumer, m, face, spin,
            arrayOf(rot(-halfW, -halfH), rot(halfW, -halfH), rot(halfW, halfH), rot(-halfW, halfH)),
            hBot, doubleArrayOf(hTop, hTop, hTop, hTop), argb,
        )
    }

    /**
     * The core solid: a quad footprint (corners in grid space, order TL,TR,BR,BL)
     * extruded from [hBot] to per-corner [hTops] (unequal tops = a wedge — the
     * toggle lever). Front face full colour; walls shaded (top light, bottom
     * dark, sides mid) for the fake-lit tactile look.
     */
    private fun prism(
        consumer: VertexConsumer,
        m: Matrix4f,
        face: Direction,
        spin: Int,
        corners: Array<DoubleArray>,
        hBot: Double,
        hTops: DoubleArray,
        argb: Int,
    ) {
        val top = Array(4) { PanelSurface.local(corners[it][0], corners[it][1], face, spin, hTops[it]) }
        val bot = Array(4) { PanelSurface.local(corners[it][0], corners[it][1], face, spin, hBot) }
        // Front face (TL,BL,BR,TR order like rect()).
        quad(consumer, m, top[0], top[3], top[2], top[1], argb)
        // Walls: 0-1 top edge, 1-2 right, 2-3 bottom, 3-0 left.
        wall(consumer, m, bot[0], bot[1], top[1], top[0], shade(argb, 0.85f))
        wall(consumer, m, bot[1], bot[2], top[2], top[1], shade(argb, 0.70f))
        wall(consumer, m, bot[2], bot[3], top[3], top[2], shade(argb, 0.55f))
        wall(consumer, m, bot[3], bot[0], top[0], top[3], shade(argb, 0.70f))
    }

    private fun wall(consumer: VertexConsumer, m: Matrix4f, a: FloatArray, b: FloatArray, c: FloatArray, d: FloatArray, argb: Int) =
        quad(consumer, m, a, b, c, d, argb)

    /** Double-sided colour quad (cull-proof on any panel face). */
    private fun quad(consumer: VertexConsumer, m: Matrix4f, p0: FloatArray, p1: FloatArray, p2: FloatArray, p3: FloatArray, argb: Int) {
        val a = (argb ushr 24) and 0xFF
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        fun vert(p: FloatArray) = consumer.addVertex(m, p[0], p[1], p[2]).setUv(0f, 0f).setColor(r, g, b, a)
        vert(p0); vert(p1); vert(p2); vert(p3)
        vert(p3); vert(p2); vert(p1); vert(p0)
    }

    private fun shade(argb: Int, f: Float): Int {
        val a = (argb ushr 24) and 0xFF
        val r = (((argb ushr 16) and 0xFF) * f).toInt().coerceIn(0, 255)
        val g = (((argb ushr 8) and 0xFF) * f).toInt().coerceIn(0, 255)
        val b = ((argb and 0xFF) * f).toInt().coerceIn(0, 255)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun norm(value: Double, min: Double, max: Double): Double {
        if (max == min) return 0.0
        return ((value - min) / (max - min)).coerceIn(0.0, 1.0)
    }

    // ── deferred text / video ─────────────────────────────────────────────

    private class TextDraw(
        val u0: Double,
        val v0: Double,
        val u1: Double,
        val v1: Double,
        val text: String,
        val color: Int,
        val outset: Double,
    )

    private class VideoDraw(
        val u0: Double,
        val v0: Double,
        val u1: Double,
        val v1: Double,
        val handle: java.util.UUID,
        val outset: Double,
    )

    /**
     * Draw [t]'s text fitted + centred into its grid rect at its outset,
     * oriented to the face via a basis matrix (text-x → grid-u, text-y →
     * grid-v, text-z → outward normal).
     */
    private fun drawText(m: Matrix4f, buffers: MultiBufferSource, font: Font, face: Direction, spin: Int, t: TextDraw) {
        if (t.text.isEmpty()) return
        val o = PanelSurface.local(t.u0, t.v0, face, spin, t.outset)
        val ue = PanelSurface.local(t.u1, t.v0, face, spin, t.outset)
        val ve = PanelSurface.local(t.u0, t.v1, face, spin, t.outset)
        val ux = floatArrayOf(ue[0] - o[0], ue[1] - o[1], ue[2] - o[2])
        val vy = floatArrayOf(ve[0] - o[0], ve[1] - o[1], ve[2] - o[2])
        val rectW = len3(ux)
        val rectH = len3(vy)
        val w = font.width(t.text).toFloat()
        if (rectW <= 0f || rectH <= 0f || w <= 0f) return
        val ud = norm3(ux)
        val vd = norm3(vy)
        val scale = minOf(rectW / w, rectH / LINE_H) * 0.85f
        val offU = (rectW - w * scale) / 2f
        val offV = (rectH - LINE_H * scale) / 2f
        val ox = o[0] + ud[0] * offU + vd[0] * offV
        val oy = o[1] + ud[1] * offU + vd[1] * offV
        val oz = o[2] + ud[2] * offU + vd[2] * offV
        val n = normalOf(face)
        val basis = Matrix4f(
            ud[0] * scale, ud[1] * scale, ud[2] * scale, 0f,
            vd[0] * scale, vd[1] * scale, vd[2] * scale, 0f,
            n[0], n[1], n[2], 0f,
            ox, oy, oz, 1f,
        )
        val full = Matrix4f(m).mul(basis)
        font.drawInBatch(t.text, 0f, 0f, t.color, false, full, buffers, Font.DisplayMode.NORMAL, 0, LightTexture.FULL_BRIGHT)
    }

    /** Blit a video handle's FBO into the element window (v=0 at the BOTTOM —
     *  FBO colour attachments are bottom-up). Double-sided. */
    private fun drawVideo(m: Matrix4f, buffers: MultiBufferSource, face: Direction, spin: Int, v: VideoDraw) {
        val surface = VideoManager.getOrCreate(v.handle)
            as? dev.nitka.nodewire.client.video.GlVideoSurface ?: return
        val consumer = buffers.getBuffer(VideoBlit.plainTypeFor(surface.colorTextureId()))
        val tl = PanelSurface.local(v.u0, v.v0, face, spin, v.outset)
        val tr = PanelSurface.local(v.u1, v.v0, face, spin, v.outset)
        val br = PanelSurface.local(v.u1, v.v1, face, spin, v.outset)
        val bl = PanelSurface.local(v.u0, v.v1, face, spin, v.outset)
        fun vert(p: FloatArray, u: Float, vv: Float) =
            consumer.addVertex(m, p[0], p[1], p[2]).setUv(u, vv).setColor(1f, 1f, 1f, 1f)
        vert(tl, 0f, 1f); vert(bl, 0f, 0f); vert(br, 1f, 0f); vert(tr, 1f, 1f)
        vert(tr, 1f, 1f); vert(br, 1f, 0f); vert(bl, 0f, 0f); vert(tl, 0f, 1f)
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

    // ── placement / link guide ────────────────────────────────────────────

    /**
     * The 16×16 grid + footprint ghost, shown on the panel the player points at
     * while holding an element item (green fits / red blocked), the Panel Key
     * (orange removal target) or the Link Tool (cyan — the element whose pin
     * arms/commits, mirroring LinkHud's pointing-wins highlight). Drawn in THIS
     * pass above the raised parts, so no separate line pass and no z-fighting.
     */
    private fun drawGuide(consumer: VertexConsumer, m: Matrix4f, be: ControlPanelBlockEntity, face: Direction, spin: Int) {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val held = player.mainHandItem.item
        val element = held as? PanelElementItem
        val isLinkTool = held is ChannelLinkToolItem
        if (element == null && held !is PanelKeyItem && !isLinkTool) return
        val hr = mc.hitResult as? BlockHitResult ?: return
        if (hr.blockPos != be.blockPos) return

        for (i in 0..16) {
            val t = i / 16.0
            rect(consumer, m, face, spin, t - GRID_HW, 0.0, t + GRID_HW, 1.0, COL_GRID, OUT_GRID)
            rect(consumer, m, face, spin, 0.0, t - GRID_HW, 1.0, t + GRID_HW, COL_GRID, OUT_GRID)
        }

        val hit = ControlPanelBlock.gridHit(be.blockState, be.blockPos, hr.location) ?: return
        if (element != null) {
            val type = PanelElements.byId(element.typeId) ?: return
            val anchor = ControlPanelBlock.clampAnchor(hit.cell, type.cols, type.rows)
            val blocked = PanelGrid.overlaps(be.occupiedCells(), anchor, type.cols, type.rows)
            ghostOutline(consumer, m, face, spin, anchor.x, anchor.y, type.cols, type.rows, if (blocked) COL_BLOCKED else COL_FREE)
        } else {
            be.elementAt(hit.cell)?.let {
                val color = if (isLinkTool) COL_LINK else COL_KEY
                ghostOutline(consumer, m, face, spin, it.cellX, it.cellY, it.cols, it.rows, color)
            }
        }
    }

    private fun ghostOutline(consumer: VertexConsumer, m: Matrix4f, face: Direction, spin: Int, cx: Int, cy: Int, cols: Int, rows: Int, color: Int) {
        val g = ELEMENT_GAP
        val u0 = (cx + g) / 16.0; val v0 = (cy + g) / 16.0
        val u1 = (cx + cols - g) / 16.0; val v1 = (cy + rows - g) / 16.0
        val w = GHOST_HW
        rect(consumer, m, face, spin, u0, v0 - w, u1, v0 + w, color, OUT_GHOST)
        rect(consumer, m, face, spin, u0, v1 - w, u1, v1 + w, color, OUT_GHOST)
        rect(consumer, m, face, spin, u0 - w, v0, u0 + w, v1, color, OUT_GHOST)
        rect(consumer, m, face, spin, u1 - w, v0, u1 + w, v1, color, OUT_GHOST)
    }

    companion object {
        /** All screen-variant type ids (shared render branch). */
        private val SCREEN_IDS = PanelElements.ALL.map { it.id }.filter { PanelElements.isScreen(it) }.toSet()

        /** Types rendered through the Dashpanels baked pipeline. */
        private val BAKED_TYPES = setOf(
            "switch", "momentary", "push_button", "key_switch", "emergency",
            "lever", "knob", "joystick", "bulb", "seven_segment", "buzzer", "label",
        )

        private const val ELEMENT_GAP = 0.06 // cell inset between an element body and its footprint
        private const val LINE_H = 8f // vanilla font line height, px

        // Heights = ABSOLUTE outsets off the mounting wall (block units).
        // Max ~0.075 ≈ 1.2px of relief — tactile but still inside the block.
        private const val OUT_PLATE = 0.001
        private const val H_TRACK = 0.022 // slider base
        private const val H_LABEL = 0.020 // label plate
        private const val H_BASE = 0.032 // element housings
        private const val H_PART = 0.062 // caps / levers / thumbs / domes
        private const val OUT_GRID = 0.006 // just above the plate surface
        private const val OUT_GHOST = 0.010
        private const val GRID_HW = 0.0016
        private const val GHOST_HW = 0.004

        private const val SELECTOR_SWEEP_DEG = 270.0

        private const val COL_PLATE = 0xFF202225.toInt()
        private const val COL_BODY = 0xFF3A4048.toInt()
        private const val COL_BODY_HI = 0xFF565E68.toInt()
        private const val COL_SLOT = 0xFF14161A.toInt()
        private const val COL_ON = 0xFF33CC44.toInt()
        private const val COL_OFF = 0xFF8A4444.toInt()
        private const val COL_BTN = 0xFF4A5C9A.toInt()
        private const val COL_PRESS = 0xFF88AAFF.toInt()
        private const val COL_MARK = 0xFFFFCC33.toInt()
        private const val COL_THUMB = 0xFFCCCCCC.toInt()
        private const val COL_LAMP_ON = 0xFFFF3333.toInt()
        private const val COL_LAMP_OFF = 0xFF331111.toInt()
        private const val COL_FILL = 0xFF33CCCC.toInt()
        private const val COL_SCREEN = 0xFF050505.toInt()
        private const val COL_LCD = 0xFF3FD24A.toInt()
        private const val COL_LABEL = 0xFF2E3238.toInt()

        private const val COL_TEXT = 0xFFE8F0F0.toInt()
        private val COL_GRID = 0xFF9AA0A6.toInt()
        private val COL_FREE = 0xFF3FD24A.toInt()
        private val COL_BLOCKED = 0xFFE0403A.toInt()
        private val COL_KEY = 0xFFE8A23A.toInt()
        private val COL_LINK = 0xFF5CC8E8.toInt()

        private var whiteTex: DynamicTexture? = null

        /** 1×1 white texture id — tinted by vertex colour for solid quads. */
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
