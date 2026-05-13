package dev.nitka.nodewire.ui.core

import androidx.compose.runtime.Composable
import dev.nitka.nodewire.ui.render.NwCanvas
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/**
 * Subclass this to write a screen with `@Composable` content. Override
 * [Content] and you're done — the parent class hooks up everything else:
 * MC's `init`/`render`/`removed` callbacks bridge to [NwUiOwner].
 *
 * Mouse and keyboard input bridges are added in Phase 10.
 *
 * One owner per screen instance: closing and reopening creates a fresh
 * composition with fresh state. This is intentional — state that should
 * survive belongs in a singleton or persisted store, not in the Screen.
 */
abstract class NwComposeScreen(title: Component) : Screen(title) {

    private val owner = NwUiOwner()

    @Composable
    protected abstract fun Content()

    override fun init() {
        super.init()
        owner.start { Content() }
    }

    override fun render(gfx: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        renderBackground(gfx)
        val canvas = NwCanvas(gfx, Minecraft.getInstance().font)
        owner.frame(canvas, this.width, this.height)
        // No super.render — we don't want MC's default widget rendering.
        // (Widgets like `addRenderableWidget` are not used by Compose screens.)
    }

    override fun removed() {
        owner.dispose()
        super.removed()
    }

    /** Compose screens stay live — game continues to tick in the background. */
    override fun isPauseScreen(): Boolean = false
}
