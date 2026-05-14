package dev.nitka.nodewire.ui.core

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import dev.nitka.nodewire.ui.input.KeyEvent
import dev.nitka.nodewire.ui.input.LocalKeyFocus
import dev.nitka.nodewire.ui.input.PointerEvent
import dev.nitka.nodewire.ui.render.NwCanvas
import dev.nitka.nodewire.ui.theme.LocalScreenSize
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation

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

    /**
     * Blur the game world behind the screen via MC's post-effect chain.
     * Activated in [init], deactivated in [removed]. The post-chain runs
     * before the screen renders, so our semi-transparent backgrounds and
     * scrim layers composite over an already-blurred frame — frosted-glass
     * look without any extra render pass on our side.
     *
     * Skipped on the title screen (no level) because `loadEffect` is fine
     * but the blur would never visibly run without a world to render.
     */
    private var blurActive = false

    @Composable
    protected abstract fun Content()

    override fun init() {
        super.init()
        val mc = Minecraft.getInstance()
        if (mc.level != null && !blurActive) {
            mc.gameRenderer.loadEffect(ResourceLocation("shaders/post/blur.json"))
            blurActive = true
        }
        // Provide the screen-size CompositionLocal at the very top of the
        // composition. The owner's screenSize state is updated each frame()
        // call, so any composable reading LocalScreenSize sees fresh values.
        owner.start {
            CompositionLocalProvider(
                LocalScreenSize provides owner.screenSize.value,
                LocalKeyFocus provides owner.keyFocusController,
            ) {
                Content()
            }
        }
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
        if (blurActive) {
            Minecraft.getInstance().gameRenderer.shutdownEffect()
            blurActive = false
        }
        super.removed()
    }

    /** Compose screens stay live — game continues to tick in the background. */
    override fun isPauseScreen(): Boolean = false

    // --- Pointer input bridge ---
    // MC's `mouse*` callbacks return `true` to consume; we forward to
    // NwUiOwner.dispatchPointer and fall back to super for unhandled events.

    override fun mouseClicked(x: Double, y: Double, btn: Int): Boolean {
        if (owner.dispatchPointer(PointerEvent.Press(x.toInt(), y.toInt(), btn))) return true
        return super.mouseClicked(x, y, btn)
    }

    override fun mouseReleased(x: Double, y: Double, btn: Int): Boolean {
        if (owner.dispatchPointer(PointerEvent.Release(x.toInt(), y.toInt(), btn))) return true
        return super.mouseReleased(x, y, btn)
    }

    override fun mouseDragged(x: Double, y: Double, btn: Int, dx: Double, dy: Double): Boolean {
        if (owner.dispatchPointer(PointerEvent.Drag(x.toInt(), y.toInt(), btn, dx.toFloat(), dy.toFloat()))) return true
        return super.mouseDragged(x, y, btn, dx, dy)
    }

    override fun mouseMoved(x: Double, y: Double) {
        owner.dispatchPointer(PointerEvent.Move(x.toInt(), y.toInt()))
        super.mouseMoved(x, y)
    }

    override fun mouseScrolled(x: Double, y: Double, delta: Double): Boolean {
        if (owner.dispatchPointer(PointerEvent.Scroll(x.toInt(), y.toInt(), delta.toFloat()))) return true
        return super.mouseScrolled(x, y, delta)
    }

    // --- Keyboard input bridge ---
    // MC distinguishes `keyPressed` (control keys: Esc, Enter, Backspace,
    // arrows, …) and `charTyped` (printable Unicode code points emitted
    // through the IME). We forward both as KeyEvent.{Press, Char} to the
    // owner; the focused KeyHandler decides what to consume.

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (owner.dispatchKey(KeyEvent.Press(keyCode, scanCode, modifiers))) return true
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    override fun keyReleased(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (owner.dispatchKey(KeyEvent.Release(keyCode, scanCode, modifiers))) return true
        return super.keyReleased(keyCode, scanCode, modifiers)
    }

    override fun charTyped(codePoint: Char, modifiers: Int): Boolean {
        if (owner.dispatchKey(KeyEvent.Char(codePoint.code, modifiers))) return true
        return super.charTyped(codePoint, modifiers)
    }
}
