package dev.nitka.nodewire.ui.render

import dev.nitka.nodewire.ui.core.UiNode

/**
 * Like [SurfaceRenderer], but pushes the pose matrix forward in Z so all
 * subsequent draws sort above the main tree. Used at popup / overlay
 * boundaries.
 *
 * The base [SurfaceRenderer] already flushes before painting its
 * background, so the explicit `flush()` previously done here is folded
 * into that path. The Z push remains: it mirrors vanilla MC's tooltip
 * layer offset and gives a wide depth-test margin over main content,
 * which matters for any vanilla overlay that does honour depth (item
 * tooltips on top of our popup, etc.).
 */
object FlushingSurfaceRenderer : Renderer {
    /** Same offset vanilla MC uses for its tooltip layer — comfortably above HUD/GUI. */
    private const val POPUP_Z = 200f

    override fun NwCanvas.render(node: UiNode) {
        // Belt-and-suspenders: popups without their own BackgroundModifier
        // wouldn't trigger SurfaceRenderer's per-bg flush. Flush here so
        // queued main-tree text never floats over popup contents.
        flush()
        gfx.pose().pushPose()
        gfx.pose().translate(0f, 0f, POPUP_Z)
        with(SurfaceRenderer) { render(node) }
    }

    override fun NwCanvas.renderAfterChildren(node: UiNode) {
        with(SurfaceRenderer) { renderAfterChildren(node) }
        gfx.pose().popPose()
    }
}
