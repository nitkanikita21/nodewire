package dev.nitka.nodewire.ui.dev

import androidx.compose.runtime.Composable
import dev.nitka.nodewire.ui.core.NwComposeScreen
import dev.nitka.nodewire.ui.render.Color
import dev.nitka.nodewire.ui.render.NwCanvas
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component

/**
 * Phase 5+smoke-test: empty Compose content (Phase 6 adds Layout primitives),
 * but we draw a debug rect + label directly via [NwCanvas] in `render()` so
 * we can visually confirm the GuiGraphics → NwCanvas pipeline works.
 *
 * Bound to the `N` key by [NodewireClient]. Open in-world, ESC to close.
 */
class DemoScreen : NwComposeScreen(Component.literal("Nodewire Demo")) {
    @Composable
    override fun Content() {
        // Phase 6 will populate this with Box/Spacer/Row/Column.
    }

    override fun render(gfx: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.render(gfx, mouseX, mouseY, partialTick)
        // Smoke test: prove NwCanvas can paint outside Compose. Once Phase 6
        // lands, this block goes away — Content() will own the screen.
        val canvas = NwCanvas(gfx, Minecraft.getInstance().font)
        val w = 200
        val h = 80
        val x = (width - w) / 2
        val y = (height - h) / 2
        canvas.fillRect(x, y, w, h, Color(0xFF_22_28_3A.toInt()))
        canvas.drawBorder(x, y, w, h, 2, Color(0xFF_88_BB_FF.toInt()))
        canvas.drawText("Nodewire UI — Phase 5 smoke test", x + 10, y + 10, Color.White)
        canvas.drawText("Compose mounts, NwCanvas paints.", x + 10, y + 28, Color.White)
        canvas.drawText("ESC to close.", x + 10, y + 50, Color(0xFF_AA_AA_AA.toInt()))
    }
}
