package dev.nitka.nodewire.client.video

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferUploader
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.Tesselator
import com.mojang.blaze3d.vertex.VertexFormat
import dev.nitka.nodewire.script.Video
import dev.nitka.nodewire.script.VideoCanvas
import dev.nitka.nodewire.ui.render.Color
import dev.nitka.nodewire.ui.render.NwCanvas
import net.minecraft.client.renderer.GameRenderer
import java.util.UUID

/**
 * GL-backed [VideoCanvas] over the mod's existing 2D primitive surface
 * [NwCanvas]. Each verb runs the pure [VideoDrawClamps] (the DoS choke point)
 * then delegates to the vetted [NwCanvas] primitive — `fillRect`, `drawBorder`,
 * `drawText` (`ui/render/NwCanvas.kt:49,59,74`). `NwCanvas` renders into
 * whatever `RenderTarget` is currently GL-bound, so [VideoFrameRenderer] binds
 * the handle's FBO around the script's `frame()` body.
 *
 * **GL-touching → not unit-tested.** The clamp behaviour is covered headless via
 * `VideoDrawClamps` + a recording fake; correctness of the [NwCanvas] delegation
 * is by inspection + user `runClient`.
 *
 * Lives in `client.video` (DENY'd by the script sandbox), so the script can
 * only ever hold the [VideoCanvas] interface, never this impl or its `NwCanvas`.
 */
class NwCanvasVideoCanvas(
    private val nw: NwCanvas,
    private val surfaceW: Int = VideoManager.STANDARD_SIZE,
    private val surfaceH: Int = surfaceW,
    private val dtSeconds: Float = 0f,
    private val timeSeconds: Float = 0f,
    private val frameIndex: Long = 0L,
    /** The handle this canvas draws INTO — used to reject a self-blit (a video
     *  drawn into itself would read+write the same FBO). */
    private val destHandle: UUID = UUID(0L, 0L),
) : VideoCanvas {

    override fun width(): Int = surfaceW

    override fun height(): Int = surfaceH

    override fun dt(): Float = dtSeconds

    override fun time(): Float = timeSeconds

    override fun frames(): Long = frameIndex

    override fun clear(color: Long) {
        // Full-surface fill (the `clear` GAP verb — spec §2).
        nw.fillRect(0, 0, surfaceW, surfaceH, color.toColor())
    }

    override fun rect(x: Int, y: Int, w: Int, h: Int, color: Long) {
        val r = VideoDrawClamps.rect(x, y, w, h, surfaceW, surfaceH)
        if (r.w <= 0 || r.h <= 0) return
        nw.fillRect(r.x, r.y, r.w, r.h, color.toColor())
    }

    override fun border(x: Int, y: Int, w: Int, h: Int, thickness: Int, color: Long) {
        val r = VideoDrawClamps.rect(x, y, w, h, surfaceW, surfaceH)
        if (r.w <= 0 || r.h <= 0) return
        nw.drawBorder(r.x, r.y, r.w, r.h, VideoDrawClamps.thickness(thickness, maxOf(surfaceW, surfaceH)), color.toColor())
    }

    override fun line(x1: Int, y1: Int, x2: Int, y2: Int, color: Long) {
        // v1: axis-aligned only — degrade to the bounding strip (a 1-px-thick
        // filled rect spanning the two clamped endpoints). True diagonals are
        // out of scope; documented on the facade.
        val cx1 = VideoDrawClamps.coord(x1, surfaceW)
        val cy1 = VideoDrawClamps.coord(y1, surfaceH)
        val cx2 = VideoDrawClamps.coord(x2, surfaceW)
        val cy2 = VideoDrawClamps.coord(y2, surfaceH)
        val left = minOf(cx1, cx2)
        val top = minOf(cy1, cy2)
        val w = (maxOf(cx1, cx2) - left).coerceAtLeast(1)
        val h = (maxOf(cy1, cy2) - top).coerceAtLeast(1)
        nw.fillRect(left, top, w, h, color.toColor())
    }

    override fun text(s: String, x: Int, y: Int, color: Long) {
        nw.drawText(
            VideoDrawClamps.text(s),
            VideoDrawClamps.coord(x, surfaceW),
            VideoDrawClamps.coord(y, surfaceH),
            color.toColor(),
            shadow = false,
        )
    }

    // Real font metrics for the `ui {}` layout DSL — measured on the same
    // (clamped) string that [text] would draw, so layout width == drawn width.
    override fun textWidth(s: String): Int = nw.measureText(VideoDrawClamps.text(s)).width

    override fun lineHeight(): Int = nw.measureText("").height

    override fun renderUi(root: dev.nitka.nodewire.script.ui.UiSpec) =
        VideoUiLayout.render(this, root)

    override fun project(video: Video, x: Double, y: Double, z: Double): dev.nitka.nodewire.script.Vec2? {
        if (video.handle == UUID(0L, 0L)) return null
        val px = dev.nitka.nodewire.client.camera.CameraProjection.projectToCanvas(
            video.handle, surfaceW, surfaceH, x, y, z,
        ) ?: return null
        return dev.nitka.nodewire.script.Vec2(px[0], px[1])
    }

    override fun image(video: Video, x: Int, y: Int, w: Int, h: Int) {
        // Nil handle (unbound / not-yet-replicated input) — nothing to show.
        if (video.handle == UUID(0L, 0L)) return
        // Self-blit would sample + write the same FBO — skip.
        if (video.handle == destHandle) return
        val r = VideoDrawClamps.rect(x, y, w, h, surfaceW, surfaceH)
        if (r.w <= 0 || r.h <= 0) return
        val src = VideoManager.getOrCreate(video.handle) as? GlVideoSurface ?: return
        val texId = src.colorTextureId()
        if (texId <= 0) return

        // Flush GuiGraphics draws buffered BEFORE this call (e.g. a clear()) so
        // they land UNDER the feed; the textured quad below draws immediately to
        // the bound FBO, and later verbs flush on top at the end of the frame.
        nw.flush()

        val x0 = r.x.toFloat()
        val y0 = r.y.toFloat()
        val x1 = (r.x + r.w).toFloat()
        val y1 = (r.y + r.h).toFloat()
        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        RenderSystem.disableCull()
        RenderSystem.setShader { GameRenderer.getPositionTexShader() }
        RenderSystem.setShaderTexture(0, texId)
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f)
        val buf = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX)
        // FBO colour textures are bottom-up → flip V so the source shows upright.
        buf.addVertex(x0, y0, 0f).setUv(0f, 1f) // top-left
        buf.addVertex(x0, y1, 0f).setUv(0f, 0f) // bottom-left
        buf.addVertex(x1, y1, 0f).setUv(1f, 0f) // bottom-right
        buf.addVertex(x1, y0, 0f).setUv(1f, 1f) // top-right
        BufferUploader.drawWithShader(buf.buildOrThrow())
    }

    /** Narrow the low 32 bits of the packed-ARGB `Long` to the mod's [Color]. */
    private fun Long.toColor(): Color = Color((this and 0xFFFFFFFFL).toInt())
}
