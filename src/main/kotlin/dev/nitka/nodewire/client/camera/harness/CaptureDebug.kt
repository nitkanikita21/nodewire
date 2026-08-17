package dev.nitka.nodewire.client.camera.harness

import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.logging.LogUtils
import net.minecraft.client.Screenshot
import net.neoforged.fml.loading.FMLPaths
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL30
import java.nio.file.Files
import java.util.UUID

/**
 * One-shot render-debug dump for the capture pipeline, armed with
 * `/nodewire capture dump`. On the next capture batch it:
 *
 *  * logs the raw GL state (write masks, depth test, blend, scissor, bound
 *    FBO, viewport) right before each feed's clear — the states that make
 *    `glClear` silently no-op are exactly the ones we keep getting bitten by;
 *  * saves each feed's color buffer as a PNG under
 *    `screenshots/nodewire-debug/` and logs depth-buffer statistics
 *    (min/mean/max + how many texels still sit at the 1.0 clear value), so
 *    "is the depth clear actually happening" stops being a guess.
 */
object CaptureDebug {

    private val LOG = LogUtils.getLogger()

    @Volatile
    private var armed = false

    fun request() {
        armed = true
    }

    // ── blink sampler ─────────────────────────────────────────────────────
    // Logs Sodium's visible-section count per frame and flags dips, so we can
    // tell "sections vanish from the render lists" from "sections are in the
    // lists but drawn wrong" — the two halves the blink could still live in.

    @Volatile
    private var blinkFramesLeft: Int = 0
    private var blinkAvg: Double = 0.0
    private var blinkCapturedThisFrame = false

    fun armBlinkDiag(frames: Int) {
        blinkFramesLeft = frames
        blinkAvg = 0.0
    }

    fun blinkArmed(): Boolean = blinkFramesLeft > 0

    fun noteCaptureThisFrame() {
        blinkCapturedThisFrame = true
    }

    fun sampleFrame(count: Int) {
        if (blinkFramesLeft <= 0 || count < 0) {
            blinkCapturedThisFrame = false
            return
        }
        blinkFramesLeft -= 1
        if (blinkAvg == 0.0) blinkAvg = count.toDouble()
        val dip = count < blinkAvg * 0.85
        if (dip) {
            LOG.warn(
                "[NW-CAPDBG] visible sections DIP: {} (avg {}) capturedLastFrame={}",
                count, blinkAvg.toInt(), blinkCapturedThisFrame,
            )
        }
        blinkAvg = blinkAvg * 0.9 + count * 0.1
        if (blinkFramesLeft == 0) {
            LOG.info("[NW-CAPDBG] blink sampler finished (avg {} sections)", blinkAvg.toInt())
        }
        blinkCapturedThisFrame = false
    }

    fun isArmed(): Boolean = armed

    fun disarm() {
        armed = false
    }

    fun logGlState(tag: String) {
        runCatching {
            val colorMask = BufferUtils.createByteBuffer(4)
            GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, colorMask)
            val viewport = BufferUtils.createIntBuffer(16)
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport)
            val scissorBox = BufferUtils.createIntBuffer(16)
            GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, scissorBox)
            LOG.info(
                "[NW-CAPDBG] {} | depthMask={} colorMask=[{},{},{},{}] depthTest={} blend={} scissorTest={} " +
                    "scissorBox=[{},{},{},{}] drawFbo={} viewport=[{},{},{},{}]",
                tag,
                GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK),
                colorMask[0].toInt() != 0, colorMask[1].toInt() != 0,
                colorMask[2].toInt() != 0, colorMask[3].toInt() != 0,
                GL11.glIsEnabled(GL11.GL_DEPTH_TEST),
                GL11.glIsEnabled(GL11.GL_BLEND),
                GL11.glIsEnabled(GL11.GL_SCISSOR_TEST),
                scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3],
                GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING),
                viewport[0], viewport[1], viewport[2], viewport[3],
            )
        }.onFailure { LOG.warn("[NW-CAPDBG] state read failed: {}", it.toString()) }
    }

    /** Save any render target as a PNG next to the feed dumps. */
    fun dumpTarget(name: String, target: RenderTarget) {
        runCatching {
            val dir = FMLPaths.GAMEDIR.get().resolve("screenshots").resolve("nodewire-debug")
            Files.createDirectories(dir)
            val stamp = System.currentTimeMillis()
            Screenshot.takeScreenshot(target).use { img ->
                img.writeToFile(dir.resolve("$name-$stamp.png").toFile())
            }
            LOG.info("[NW-CAPDBG] {} {}x{} saved", name, target.width, target.height)
        }.onFailure { LOG.warn("[NW-CAPDBG] {} dump failed: {}", name, it.toString()) }
    }

    fun dumpFeed(handle: UUID, target: RenderTarget) {
        runCatching {
            val dir = FMLPaths.GAMEDIR.get().resolve("screenshots").resolve("nodewire-debug")
            Files.createDirectories(dir)
            val stamp = System.currentTimeMillis()
            val short = handle.toString().substring(0, 8)

            Screenshot.takeScreenshot(target).use { img ->
                img.writeToFile(dir.resolve("feed-$short-$stamp.png").toFile())
            }

            // Depth statistics straight off the feed's depth attachment.
            val w = target.width
            val h = target.height
            val prevRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING)
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, target.frameBufferId)
            val buf = BufferUtils.createFloatBuffer(w * h)
            GL11.glReadPixels(0, 0, w, h, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, buf)
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead)
            var min = 1f
            var max = 0f
            var sum = 0.0
            var atClear = 0
            for (i in 0 until w * h) {
                val d = buf[i]
                if (d < min) min = d
                if (d > max) max = d
                sum += d
                if (d >= 0.9999f) atClear++
            }
            LOG.info(
                "[NW-CAPDBG] feed {} {}x{} | depth min={} max={} mean={} atClear={}% | png saved",
                short, w, h, min, max, (sum / (w * h)).toFloat(),
                (atClear * 100L / (w * h)),
            )
        }.onFailure { LOG.warn("[NW-CAPDBG] feed dump failed: {}", it.toString()) }
    }
}
