package dev.nitka.nodewire.client.video

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.VertexSorting
import dev.nitka.nodewire.script.VideoCanvas
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import org.joml.Matrix4f
import org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT
import org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT
import java.util.UUID

/**
 * Runtime-owned bind → draw → unbind dance that retargets the mod's [NwCanvas]
 * 2D surface from the screen onto a Video handle's offscreen FBO, runs the
 * script's `frame()` draw closure against a sandbox-safe [VideoCanvas] facade,
 * then restores the prior render target.
 *
 * **The sandbox boundary lives here (Findings F4/F6/F7).** The script body is
 * handed the [VideoCanvas] facade **only**; the [GlVideoSurface] cast, the
 * `bindWrite`/`unbindWrite`, the `GuiGraphics`, and `Minecraft` are all inside
 * this object and never escape to the script — none of these types is even
 * loadable in the script sandbox (`client.video.*` + the engine types are
 * DENY'd by `SandboxClassLoader`).
 *
 * **GL-state safety:** the bind/draw/unbind runs in **try/finally** so the prior
 * main render target is rebound even if the script's `block` throws (F6).
 *
 * **Cadence:** redraw frequency is gated by [VideoCadence] (F8) — a `frame()`
 * that requests a redraw every client frame is throttled, so it cannot force an
 * unbounded number of full-surface GL passes. The script controls *what*; the
 * runtime controls *how often*.
 *
 * **GL-touching → not unit-tested.** Verified by user `runClient`. The pieces
 * that *are* testable (clamps, cadence) are split out into [VideoDrawClamps] /
 * [VideoCadence].
 */
object VideoFrameRenderer {

    /** Per-handle frame timing for the [VideoCanvas] dt()/time()/frames() helpers.
     *  Render-thread only (no sync needed); a leaked entry per never-freed handle
     *  is negligible. */
    private val firstMs = HashMap<UUID, Long>()
    private val lastMs = HashMap<UUID, Long>()
    private val frameCount = HashMap<UUID, Long>()

    /**
     * Bind [handle]'s FBO, run [block] against a [VideoCanvas], then unbind and
     * restore the previous main target. No-op (returns false) when [VideoManager]
     * is mid-capture (Camera recursion guard, inert until Component 4 exists) or
     * when [handle] is the nil handle.
     *
     * Must be called on the render thread (both this and the BER bind GL there).
     *
     * @return true iff a draw pass actually ran.
     */
    fun drawInto(handle: UUID, block: (VideoCanvas) -> Unit): Boolean {
        // Inert Camera recursion guard (always false until Component 4 lands).
        if (VideoManager.isCapturing()) return false

        val mc = Minecraft.getInstance()
        val surface = VideoManager.getOrCreate(handle) as? GlVideoSurface ?: return false
        val target = surface.target()
        // Public getter `getMainRenderTarget()` (NOT the private
        // `mainRenderTarget` field, which would need an Access Transformer — out
        // of scope for the foundation). Kotlin maps the property to the getter.
        val mainTarget = mc.mainRenderTarget

        // We draw during RenderLevelStage — the world's 3D perspective projection
        // + camera modelview are live. GuiGraphics expects a 2D GUI ortho with a
        // top-left, y-down origin and an identity modelview; without swapping them
        // every fillRect/text is transformed by the 3D matrices and lands off the
        // surface, leaving the FBO at its cleared (black) state — a black face with
        // nothing on it. Save, set a surface-sized ortho + identity modelview,
        // restore in finally.
        val savedProj = RenderSystem.getProjectionMatrix()
        val savedSort = RenderSystem.getVertexSorting()
        val savedColor = RenderSystem.getShaderColor().clone()
        val savedFogStart = RenderSystem.getShaderFogStart()
        val savedFogEnd = RenderSystem.getShaderFogEnd()
        val mv = RenderSystem.getModelViewStack()

        // bindWrite(true) sets the viewport to the target's size (the standard
        // square), so GuiGraphics draws land in surface space.
        target.bindWrite(/* setViewport = */ true)
        RenderSystem.setProjectionMatrix(
            Matrix4f().setOrtho(0f, surface.width.toFloat(), surface.height.toFloat(), 0f, -1000f, 1000f),
            VertexSorting.ORTHOGRAPHIC_Z,
        )
        mv.pushMatrix()
        mv.identity()
        RenderSystem.applyModelViewMatrix()
        // Clear the FBO's colour + DEPTH. GuiGraphics layers fills under text via
        // per-layer Z + the depth buffer; a stale (uncleared) FBO depth makes a
        // later fill (the background) win the depth test and HIDE the text. Don't
        // disable depth test — the GUI render types manage it themselves.
        RenderSystem.clearColor(0f, 0f, 0f, 0f)
        RenderSystem.clear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX)
        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        // We draw mid-RenderLevelStage, where the world renderer may have left the
        // shader colour-modulator at a fog/biome tint. GuiGraphics fills + font
        // MULTIPLY by it, so without resetting to white the text/rects come out
        // tinted ("gradient"/wrong colour) — intermittently, depending on the
        // world frame's leftover state. Force the modulator white for the pass.
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f)
        // FONT FOG — the load-bearing fix. `rendertype_text` (and several GUI
        // shaders) apply FOG, and we draw mid-RenderLevelStage where the live
        // world fog is the SKY colour (blue), distance-based. Our ortho maps each
        // text quad to a fog distance, so glyphs get fog-blended toward blue by
        // position — the "red text fading to blue" per-glyph gradient. Fills/camera
        // use fog-less shaders, so ONLY text was affected. Push fog out of range so
        // the pass is fog-free; restored in finally.
        RenderSystem.setShaderFogStart(1.0e7f)
        RenderSystem.setShaderFogEnd(1.0e8f)
        // 2D pass: make DRAW ORDER authoritative, not depth. The camera blit
        // (image()) is immediate and writes depth at z=0; the HUD fills/text are
        // batched GuiGraphics drawn at the same z, so with depth-test on, full-
        // surface fills (the telemetry strips) lost the depth tie and vanished
        // under the camera. Disable depth for the pass — fills/text then layer
        // purely by issue order (bg before text in the script), restored after.
        RenderSystem.disableDepthTest()
        val gfx = GuiGraphics(mc, mc.renderBuffers().bufferSource())
        try {
            val now = net.minecraft.Util.getMillis()
            val first = firstMs.getOrPut(handle) { now }
            val dt = lastMs[handle]?.let { (now - it) / 1000f } ?: 0f
            lastMs[handle] = now
            val frames = frameCount[handle] ?: 0L
            frameCount[handle] = frames + 1
            val canvas = dev.nitka.nodewire.ui.render.NwCanvas(gfx, mc.font)
            block(
                NwCanvasVideoCanvas(
                    canvas,
                    surface.width,
                    surface.height,
                    dtSeconds = dt,
                    timeSeconds = (now - first) / 1000f,
                    frameIndex = frames,
                    destHandle = handle,
                ),
            )
            gfx.flush()
        } finally {
            // Restore prior GL state EVEN IF block threw (F6).
            target.unbindWrite()
            mainTarget.bindWrite(/* setViewport = */ true)
            mv.popMatrix()
            RenderSystem.applyModelViewMatrix()
            RenderSystem.setProjectionMatrix(savedProj, savedSort)
            RenderSystem.setShaderColor(savedColor[0], savedColor[1], savedColor[2], savedColor[3])
            RenderSystem.setShaderFogStart(savedFogStart)
            RenderSystem.setShaderFogEnd(savedFogEnd)
            RenderSystem.enableDepthTest()
        }
        return true
    }
}
