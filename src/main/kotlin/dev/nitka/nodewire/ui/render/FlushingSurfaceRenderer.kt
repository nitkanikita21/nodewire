package dev.nitka.nodewire.ui.render

import dev.nitka.nodewire.ui.core.UiNode

/**
 * Like [SurfaceRenderer], but pushes the pose matrix forward in Z (+200,
 * mirroring vanilla MC's tooltip layer). Used at popup / overlay boundaries.
 *
 * Layering note: popups appear above main content because they are LATER
 * SIBLINGS in OverlayHost's Box (submission order), NOT because of the Z
 * push — GuiGraphics.flush() disables the depth test around endBatch and
 * every GUI primitive self-flushes, so content-vs-popup ordering is purely
 * painter-order. The +200 Z only reserves headroom UNDER genuinely
 * depth-tested vanilla overlays (e.g. item tooltips drawn via drawManaged
 * at Z=400), so our popups don't punch through them.
 *
 * No flush is needed here: MC's drawString self-flushes after every string
 * and the shared GUI buffer flushes on each gui<->text render-type switch,
 * so within-frame draw order already equals submission order.
 */
object FlushingSurfaceRenderer : Renderer {
    /** Same offset vanilla MC uses for its tooltip layer — comfortably above HUD/GUI. */
    private const val POPUP_Z = 200f

    override fun NwCanvas.render(node: UiNode) {
        gfx.pose().pushPose()
        gfx.pose().translate(0f, 0f, POPUP_Z)
        with(SurfaceRenderer) { render(node) }
    }

    override fun NwCanvas.renderAfterChildren(node: UiNode) {
        with(SurfaceRenderer) { renderAfterChildren(node) }
        gfx.pose().popPose()
    }
}
