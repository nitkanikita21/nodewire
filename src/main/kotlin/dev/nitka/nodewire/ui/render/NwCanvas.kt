package dev.nitka.nodewire.ui.render

import dev.nitka.nodewire.ui.layout.IntOffset
import dev.nitka.nodewire.ui.layout.IntSize
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation

/**
 * Drawing surface for a single Screen render pass. Wraps Minecraft's
 * [GuiGraphics] and tracks an offset stack so renderers can work in their
 * node's local coordinates while we transparently translate to screen pixels.
 *
 * Instances are created per-frame in `NwUiOwner.frame()` and discarded; they
 * hold no long-lived state besides the offset stack which is drained back to
 * `[IntOffset.Zero]` between frames.
 */
class NwCanvas(val gfx: GuiGraphics, val font: Font) {
    /**
     * Offset stack — `last()` is the active translation applied to every
     * draw call. We start at zero and the paint walk pushes each child's
     * `(layoutX, layoutY)` before recursing.
     */
    private val offsets = ArrayDeque<IntOffset>().apply { add(IntOffset.Zero) }

    /**
     * Current accumulated offset (root → current node). Public because
     * renderers that bypass [fillRect]/[drawText] (e.g. text with non-1 scale
     * that needs `gfx.pose().scale(...)`) need to know where the canvas is
     * already translated to.
     */
    val offsetX: Int get() = offsets.last().x
    val offsetY: Int get() = offsets.last().y
    private val ox: Int get() = offsetX
    private val oy: Int get() = offsetY

    fun pushOffset(dx: Int, dy: Int) {
        val o = offsets.last()
        offsets.addLast(IntOffset(o.x + dx, o.y + dy))
    }

    fun popOffset() {
        check(offsets.size > 1) { "popOffset() without matching pushOffset()" }
        offsets.removeLast()
    }

    /** Solid axis-aligned fill in node-local coordinates. */
    fun fillRect(x: Int, y: Int, width: Int, height: Int, color: Color) {
        if (width <= 0 || height <= 0 || color.a == 0) return
        gfx.fill(ox + x, oy + y, ox + x + width, oy + y + height, color.argb)
    }

    /**
     * Stroke a rectangle border `thickness` pixels thick, drawn INSIDE the
     * rect bounds (so a 1-px border on a 10×10 rect leaves an 8×8 inner area).
     * For consistent compositing we paint as 4 non-overlapping rects.
     */
    fun drawBorder(x: Int, y: Int, width: Int, height: Int, thickness: Int, color: Color) {
        if (thickness <= 0 || color.a == 0 || width <= 0 || height <= 0) return
        val t = thickness.coerceAtMost(minOf(width, height) / 2 + 1)
        fillRect(x, y, width, t, color)                       // top
        fillRect(x, y + height - t, width, t, color)          // bottom
        fillRect(x, y + t, t, height - 2 * t, color)          // left (between top/bottom strips)
        fillRect(x + width - t, y + t, t, height - 2 * t, color) // right
    }

    /** Single-line text. Uses MC font; `color.a` is honored. */
    fun drawText(text: Component, x: Int, y: Int, color: Color, shadow: Boolean = true) {
        if (color.a == 0) return
        gfx.drawString(font, text, ox + x, oy + y, color.argb, shadow)
    }

    fun drawText(text: String, x: Int, y: Int, color: Color, shadow: Boolean = true) {
        if (color.a == 0) return
        gfx.drawString(font, text, ox + x, oy + y, color.argb, shadow)
    }

    /** Measure a single line of text. Multi-line text is the caller's problem. */
    fun measureText(text: Component): IntSize =
        IntSize(font.width(text), font.lineHeight)

    fun measureText(text: String): IntSize =
        IntSize(font.width(text), font.lineHeight)

    /**
     * Blit a region of a texture at (x,y) sized (width,height).
     *
     * Defaults assume the texture is exactly the target size with the source
     * region starting at (0,0). For sprite-sheet usage, pass `u`/`v`/`texWidth`/`texHeight`.
     */
    fun drawTexture(
        loc: ResourceLocation,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        u: Float = 0f,
        v: Float = 0f,
        regionWidth: Int = width,
        regionHeight: Int = height,
        texWidth: Int = width,
        texHeight: Int = height,
    ) {
        gfx.blit(loc, ox + x, oy + y, width, height, u, v, regionWidth, regionHeight, texWidth, texHeight)
    }

    /**
     * Push a scissor clip rect in node-local coords. Subsequent draws are
     * clipped until [popClip]. MC's scissor stack only goes one level deep,
     * so nested clips intersect with the outer rect manually — caller must
     * pop before pushing a new one for now (TODO: implement nested clip
     * intersection if we hit a use case).
     */
    fun pushClip(x: Int, y: Int, width: Int, height: Int) {
        gfx.enableScissor(ox + x, oy + y, ox + x + width, oy + y + height)
    }

    fun popClip() {
        gfx.disableScissor()
    }

    /**
     * Commits any buffered geometry (notably text — `gfx.drawString` queues
     * into a separate buffer that's normally flushed at the end of the GUI
     * frame). Call before painting a popup / overlay so prior text doesn't
     * render *above* the popup's background.
     *
     * Cost: forces a small render-state batch — fine for the handful of
     * popups per frame, don't sprinkle inside hot paint loops.
     */
    fun flush() {
        gfx.flush()
    }

    /**
     * Render a vanilla item icon at node-local (x, y), 16×16 vanilla style,
     * with the count/durability decorations on top. Honors the offset stack.
     */
    fun drawItem(stack: net.minecraft.world.item.ItemStack, x: Int, y: Int) {
        if (stack.isEmpty) return
        gfx.renderItem(stack, ox + x, oy + y)
        gfx.renderItemDecorations(font, stack, ox + x, oy + y)
    }
}
