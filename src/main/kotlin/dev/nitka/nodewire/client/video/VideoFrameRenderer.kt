package dev.nitka.nodewire.client.video

import dev.nitka.nodewire.script.VideoCanvas
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
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

        // bindWrite(true) sets the viewport to the target's size (the standard
        // square), so GuiGraphics draws land in surface space.
        target.bindWrite(/* setViewport = */ true)
        val gfx = GuiGraphics(mc, mc.renderBuffers().bufferSource())
        try {
            val canvas = dev.nitka.nodewire.ui.render.NwCanvas(gfx, mc.font)
            block(NwCanvasVideoCanvas(canvas, surface.width))
            gfx.flush()
        } finally {
            // Restore prior GL state EVEN IF block threw (F6): flush is best-effort
            // already done in try; here we always unbind + rebind the main target.
            target.unbindWrite()
            mainTarget.bindWrite(/* setViewport = */ true)
        }
        return true
    }
}
